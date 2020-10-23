package ch.epfl.bluebrain.nexus.cli.modules.influx

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.Unexpected
import ch.epfl.bluebrain.nexus.cli.ProjectionPipes._
import ch.epfl.bluebrain.nexus.cli.clients._
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, PrintConfig}
import ch.epfl.bluebrain.nexus.cli.config.influx.{InfluxConfig, TypeConfig}
import ch.epfl.bluebrain.nexus.cli.sse.{EventStream, Offset}
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, ClientErrOr, Console}
import fs2.Stream
import retry.CatsEffect._
import retry.{RetryDetails, RetryPolicy}
import retry.syntax.all._

class InfluxProjection[F[_]: ContextShift](
    console: Console[F],
    esc: EventStreamClient[F],
    spc: SparqlClient[F],
    inc: InfluxClient[F],
    cfg: AppConfig
)(implicit blocker: Blocker, F: ConcurrentEffect[F], T: Timer[F]) {

  private val ic: InfluxConfig                     = cfg.influx
  implicit private val printCfg: PrintConfig       = ic.print
  implicit private val c: Console[F]               = console
  implicit private val retryPolicy: RetryPolicy[F] = cfg.env.httpClient.retry.retryPolicy

  def run: F[Unit] =
    for {
      _           <- console.println("Starting influxDB projection...")
      _           <- inc.createDb
      offset      <- Offset.load(ic.offsetFile)
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
              ic.projects
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
          val query = tc.query
            .replace("{resource_id}", ev.resourceId.renderString)
            .replace("{resource_type}", tc.tpe)
            .replace("{resource_project}", s"${org.show}/${proj.show}")
            .replace("{event_rev}", ev.rev.toString)
          spc.query(org, proj, pc.sparqlView, query).flatMap(res => insert(tc, res))
        }
        .through(printProjectionProgress(console))
        .attempt
        .map(_.leftMap(err => Unexpected(Option(err.getMessage).getOrElse("").take(30))).map(_ => ()))
        .compile
        .lastOrError
    }

    compiledStream.retryingM(successCondition) >> F.unit
  }

  private def insert(
      tc: TypeConfig,
      res: Either[ClientError, SparqlResults]
  ): F[Either[ClientError, Unit]] =
    res match {
      case Left(err)      => F.pure(Left(err))
      case Right(results) =>
        InfluxPoint
          .fromSparqlResults(results, tc)
          .traverse { point => inc.write(point) }
          .map(_.foldM(())((_, r) => r))
    }

  private def writeOffsetPeriodically(sseStream: EventStream[F]): F[Unit] =
    Stream
      .repeatEval {
        sseStream.currentEventId().flatMap {
          case Some(offset) => offset.write(ic.offsetFile)
          case None         => F.unit
        }
      }
      .metered(ic.offsetSaveInterval)
      .compile
      .drain
}
