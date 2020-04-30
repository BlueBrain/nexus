package ch.epfl.bluebrain.nexus.cli.modules.postgres

import java.nio.file.{Path, StandardOpenOption}
import java.time.Instant

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.clients.SparqlResults.{Binding, Literal}
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, SparqlClient, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.config.postgres.QueryConfig
import ch.epfl.bluebrain.nexus.cli.sse.{EventStream, Offset}
import com.github.ghik.silencer.silent
import doobie.util.Put
import doobie.util.fragment.Elem
import doobie.util.transactor.Transactor
import fs2.{io, text, Stream}
import retry.RetryDetails

class PostgresProjection[F[_]: ContextShift](
    console: Console[F],
    esc: EventStreamClient[F],
    spc: SparqlClient[F],
    xa: Transactor[F],
    blocker: Blocker,
    cfg: AppConfig
)(implicit F: ConcurrentEffect[F], T: Timer[F]) {

  def run: F[Unit] =
    for {
      _           <- console.println("Starting projection...")
      _           <- ddl
      offset      <- readOffset(cfg.postgres.offsetFile)
      eventStream <- esc.apply(offset)
      stream      = executeStream(eventStream)
      saveOffset  = writeOffsetPeriodically(eventStream)
      _           <- F.race(stream, saveOffset)
    } yield ()

  private def executeStream(eventStream: EventStream[F]): F[Unit] = {
    eventStream.value
      .mapAccumulate(0L)((idx, tuple) => (idx + 1, tuple))
      .evalMap {
        case (idx, tuple) if idx % 100 == 0 && idx != 0L =>
          console.println(s"Read $idx events.") >> F.pure(tuple)
        case (_, tuple) => F.pure(tuple)
      }
      .flatMap {
        case (ev, org, proj) =>
          val maybeConfig = cfg.postgres.projects
            .get((org, proj))
            .flatMap(pc =>
              pc.types
                .find(typeCfg => ev.resourceTypes.map(_.toString()).contains(typeCfg.tpe))
                .map(tc => (pc, tc, ev, org, proj))
            )
          Stream.fromIterator[F](maybeConfig.iterator)
      }
      .evalMap {
        case (pc, tc, ev, org, proj) =>
          tc.queries
            .map { qc =>
              val query = qc.query
                .replaceAllLiterally("{resource_id}", ev.resourceId.renderString)
                .replaceAllLiterally("{event_rev}", ev.rev.toString)
              pc.sparqlView match {
                case Some(view) => spc.query(org, proj, view, query).flatMap(res => insert(qc, res))
                case None       => spc.query(org, proj, query).flatMap(res => insert(qc, res))
              }
            }
            .sequence
            .map(_.sequence)
      }
      .mapAccumulate((0L, 0L)) {
        case ((successes, errors), v @ Right(_)) => ((successes + 1, errors), v)
        case ((successes, errors), v @ Left(_))  => ((successes, errors + 1), v)
      }
      .evalMap {
        case ((successes, errors), v) if (successes + errors) % 100 == 0 && (successes + errors) != 0L =>
          console.println(s"Processed ${successes + errors} events (success: $successes, errors: $errors)") >> F.pure(v)
        case ((_, _), v) => F.pure(v)
      }
      .compile
      .drain
  }

  private def insert(qc: QueryConfig, res: Either[ClientError, SparqlResults]): F[Either[ClientError, Unit]] = {
    res match {
      case Left(value) => F.pure(Left(value))
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
        val elems = results.results.bindings.map { map =>
          map.toList.sortBy(_._1).map(_._2).map(binding => toElem(binding))
        }
        val connections = Fragment.const(delete).update.run :: elems.map(row => Fragment(insert, row).update.run)
        connections.sequence.transact(xa).map(_ => Right(()))
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
      case (None, Some(uri), _) =>
        Elem.Arg[String](uri.renderString, Put[String])
      case (None, None, Some(bnode)) =>
        Elem.Arg[String](bnode, Put[String])
      case (Some(Literal(lexicalForm, dataType, _)), _, _) =>
        throw new RuntimeException(s"Unknown lexicalform: '$lexicalForm', dataType: '$dataType'")
    }
  }

  @silent
  private def logError(err: Throwable, details: RetryDetails): F[Unit] =
    details match {
      case RetryDetails.WillDelayAndRetry(nextDelay, retriesSoFar, _) =>
        console.println(s"Error occurred while running the index stream: ${err.getMessage}") >>
          console.println(s"Will retry in ${nextDelay.toMillis}ms ... (retries so far: $retriesSoFar)")
      case RetryDetails.GivingUp(totalRetries, _) =>
        console.println(s"Error occurred while running the index stream: ${err.printStackTrace()}") >>
          console.println(s"Giving up ... (total retries: $totalRetries)")
    }

  private def ddl: F[Unit] = {
    import doobie._
    import doobie.implicits._
    val ddls = for {
      projectConfig <- cfg.postgres.projects.values.toList
      typeConfig    <- projectConfig.types
      queryConfig   <- typeConfig.queries
    } yield Fragment.const(queryConfig.ddl)
    val conn = ddls.map(_.update.run).sequence
    conn.transact(xa) >> F.unit
  }

  private def writeOffsetPeriodically(sseStream: EventStream[F]): F[Unit] =
    Stream
      .repeatEval {
        sseStream.currentEventId().flatMap {
          case Some(offset) => writeOffset(offset)
          case None         => F.unit
        }
      }
      .metered(cfg.postgres.offsetSaveInterval)
      .compile
      .drain

  private def readOffset(file: Path): F[Option[Offset]] = {
    io.file.exists(blocker, cfg.postgres.offsetFile).flatMap { exists =>
      if (exists)
        io.file
          .readAll(file, blocker, 1024)
          .through(text.utf8Decode)
          .through(text.lines)
          .compile
          .string
          .map(Offset(_))
      else F.pure(None)
    }
  }

  private def writeOffset(offset: Offset): F[Unit] = {
    // if the file exists => truncate and write, otherwise create parents and write
    val pipeF = io.file
      .exists(blocker, cfg.postgres.offsetFile)
      .ifM(
        F.pure(
          io.file.writeAll(
            cfg.postgres.offsetFile,
            blocker,
            List(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
          )
        ),
        io.file
          .createDirectories(blocker, cfg.postgres.offsetFile.getParent)
          .map(_ => io.file.writeAll(cfg.postgres.offsetFile, blocker))
      )
    pipeF.flatMap { write =>
      Stream(offset.asString)
        .through(text.utf8Encode)
        .through(write)
        .compile
        .drain
    }
  }
}

object PostgresProjection {
  object TimeMeta extends doobie.util.meta.TimeMeta
}
