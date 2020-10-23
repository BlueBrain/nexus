package ch.epfl.bluebrain.nexus.cli.modules.postgres

import java.time.Instant

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.Unexpected
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, ClientErrOr, Console}
import ch.epfl.bluebrain.nexus.cli.ProjectionPipes._
import ch.epfl.bluebrain.nexus.cli.clients.SparqlResults.{Binding, Literal}
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, SparqlClient, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, PrintConfig}
import ch.epfl.bluebrain.nexus.cli.config.postgres.{PostgresConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.cli.sse.{EventStream, Offset}
import doobie.util.Put
import doobie.util.fragment.Elem
import doobie.util.transactor.Transactor
import doobie.util.update.Update0
import fs2.Stream
import retry.CatsEffect._
import retry.RetryDetails.{GivingUp, WillDelayAndRetry}
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.util.control.NonFatal

class PostgresProjection[F[_]: ContextShift](
    console: Console[F],
    esc: EventStreamClient[F],
    spc: SparqlClient[F],
    xa: Transactor[F],
    cfg: AppConfig
)(implicit blocker: Blocker, F: ConcurrentEffect[F], T: Timer[F]) {

  private val pc: PostgresConfig                   = cfg.postgres
  implicit private val printCfg: PrintConfig       = pc.print
  implicit private val retryPolicy: RetryPolicy[F] = pc.retry.retryPolicy
  implicit private val c: Console[F]               = console

  def run: F[Unit] =
    for {
      _           <- console.println("Starting postgres projection...")
      _           <- ddl
      offset      <- Offset.load(pc.offsetFile)
      eventStream <- esc(offset)
      stream       = executeStream(eventStream)
      saveOffset   = writeOffsetPeriodically(eventStream)
      _           <- F.race(stream, saveOffset)
    } yield ()

  private def executeStream(eventStream: EventStream[F]): F[Unit] = {
    implicit def logOnError[A]: (ClientErrOr[A], RetryDetails) => F[Unit] =
      logRetryErrors[F, A]("executing the projection")
    def successCondition[A]                                               = cfg.env.httpClient.retry.condition.notRetryFromEither[A] _

    val compiledStream = eventStream.value.flatMap { stream =>
      stream
        .map {
          case Right((ev, org, proj)) =>
            Right(
              pc.projects
                .get((org, proj))
                .flatMap(pc =>
                  pc.types.collectFirst {
                    case tc if ev.resourceTypes.exists(_.toString == tc.tpe) => (pc, tc, ev, org, proj)
                  }
                )
            )
          case Left(err)              => Left(err)
        }
        .through(printEventProgress(console))
        .evalMap { case (pc, tc, ev, org, proj) =>
          tc.queries
            .map { qc =>
              val query = qc.query
                .replace("{resource_id}", ev.resourceId.renderString)
                .replace("{resource_type}", tc.tpe)
                .replace("{resource_project}", s"${org.show}/${proj.show}")
                .replace("{event_rev}", ev.rev.toString)
              spc.query(org, proj, pc.sparqlView, query).flatMap(res => insert(qc, res))
            }
            .sequence
            .map(_.sequence)
        }
        .through(printProjectionProgress(console))
        .attempt
        .map(_.leftMap(err => Unexpected(Option(err.getMessage).getOrElse("").take(30))).map(_ => ()))
        .compile
        .lastOrError
    }
    compiledStream.retryingM(successCondition) >> F.unit
  }

  private def insert(qc: QueryConfig, res: Either[ClientError, SparqlResults]): F[Either[ClientError, Unit]] = {
    res match {
      case Left(value)    => F.pure(Left(value))
      case Right(results) =>
        import doobie._
        import doobie.implicits._

        val delete = {
          val ids = results.results.bindings.flatMap(_.get("id").map(b => b.value).toList).mkString("'", "', '", "'")
          if (ids == "''") ""
          else
            s"""delete from ${qc.table} where id in ($ids);
               |""".stripMargin
        }

        val insert =
          s"""insert into ${qc.table} (${results.head.vars.sorted.mkString(", ")})
             |values (${results.head.vars.map(_ => "?").mkString(", ")});
             |""".stripMargin

        val elems      = results.results.bindings.map { map =>
          map.toList.sortBy(_._1).map(_._2).map(binding => toElem(binding))
        }
        val statements = Fragment.const(delete).update :: elems.map(row => Fragment(insert, row).update)

        implicit val log: (Throwable, RetryDetails) => F[Unit] = logFnForSqlStatements(statements)

        statements.map(_.run).sequence.transact(xa).retryingOnAllErrors.map(_ => Right(()))
    }
  }

  private def toElem(binding: Binding): Elem = {
    import PostgresProjection.TimeMeta.javatime._
    (binding.asLiteral, binding.asUri, binding.asBNode) match {
      case (Some(Literal(lexicalForm, dataType, _)), _, _)
          if dataType.renderString == "http://www.w3.org/2001/XMLSchema#string" =>
        Elem.Arg[String](lexicalForm, Put[String])
      case (Some(Literal(lexicalForm, dataType, _)), _, _)
          if dataType.renderString == "http://www.w3.org/2001/XMLSchema#long" =>
        Elem.Arg[Long](lexicalForm.toLong, Put[Long])
      case (Some(Literal(lexicalForm, dataType, _)), _, _)
          if dataType.renderString == "http://www.w3.org/2001/XMLSchema#int" || dataType.renderString == "http://www.w3.org/2001/XMLSchema#integer" =>
        Elem.Arg[Int](lexicalForm.toInt, Put[Int])
      case (Some(Literal(lexicalForm, dataType, _)), _, _)
          if dataType.renderString == "http://www.w3.org/2001/XMLSchema#boolean" =>
        Elem.Arg[Boolean](lexicalForm.toBoolean, Put[Boolean])
      case (Some(Literal(lexicalForm, dataType, _)), _, _)
          if dataType.renderString == "http://www.w3.org/2001/XMLSchema#dateTime" =>
        Elem.Arg[Instant](Instant.parse(lexicalForm), Put[Instant])
      case (None, Some(uri), _)                            =>
        Elem.Arg[String](uri.renderString, Put[String])
      case (None, None, Some(bnode))                       =>
        Elem.Arg[String](bnode, Put[String])
      case (Some(Literal(lexicalForm, dataType, _)), _, _) =>
        throw new RuntimeException(s"Unknown lexicalform: '$lexicalForm', dataType: '$dataType'")
    }
  }

  private def ddl: F[Unit] = {
    import doobie._
    import doobie.implicits._
    val ddls                                               = for {
      projectConfig <- pc.projects.values.toList
      typeConfig    <- projectConfig.types
      queryConfig   <- typeConfig.queries
    } yield Fragment.const(queryConfig.ddl)
    val statements                                         = ddls.map(_.update)
    implicit val log: (Throwable, RetryDetails) => F[Unit] = logFnForSqlStatements(statements)
    statements.map(_.run).sequence.transact(xa).retryingOnAllErrors >> F.unit
  }

  private def logFnForSqlStatements(statements: List[Update0]): (Throwable, RetryDetails) => F[Unit] = {
    case (NonFatal(err), WillDelayAndRetry(nextDelay, retriesSoFar, _)) =>
      console.println(s"""Error occurred while executing the following SQL statements:
                         |
                         |${statements.map("\t" + _.sql).mkString("\n")}
                         |
                         |Error message: '${Option(err.getMessage).getOrElse("no message")}'
                         |Will retry in ${nextDelay.toMillis}ms ... (retries so far: $retriesSoFar)""".stripMargin)
    case (NonFatal(err), GivingUp(totalRetries, _))                     =>
      console.println(s"""Error occurred while executing the following SQL statements:
                         |
                         |${statements.map("\t" + _.sql).mkString("\n")}
                         |
                         |Error message: '${Option(err.getMessage).getOrElse("no message")}'
                         |Giving up ... (total retries: $totalRetries)""".stripMargin)
  }

  private def writeOffsetPeriodically(sseStream: EventStream[F]): F[Unit] =
    Stream
      .repeatEval {
        sseStream.currentEventId().flatMap {
          case Some(offset) => offset.write(pc.offsetFile)
          case None         => F.unit
        }
      }
      .metered(pc.offsetSaveInterval)
      .compile
      .drain

}

object PostgresProjection {
  object TimeMeta extends doobie.util.meta.TimeMeta
}
