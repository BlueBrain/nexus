package ch.epfl.bluebrain.nexus.cli.modules.influx

import cats.effect.{Blocker, ConcurrentEffect, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.Unexpected
import ch.epfl.bluebrain.nexus.cli.ProjectionPipes._
import ch.epfl.bluebrain.nexus.cli.clients._
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.config.influx.{InfluxConfig, TypeConfig}
import ch.epfl.bluebrain.nexus.cli.sse.{EventStream, Offset, OrgLabel, ProjectLabel}
import ch.epfl.bluebrain.nexus.cli.{logRetryErrors, Console}
import fs2.Stream
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

class InfluxProjection[F[_]: ContextShift](
    console: Console[F],
    esc: EventStreamClient[F],
    spc: SparqlClient[F],
    inc: InfluxClient[F],
    cfg: AppConfig
)(implicit blocker: Blocker, F: ConcurrentEffect[F], T: Timer[F]) {

  private val ic: InfluxConfig                     = cfg.influx
  implicit private val c: Console[F]               = console
  implicit private val retryPolicy: RetryPolicy[F] = cfg.env.httpClient.retry.retryPolicy

  def run: F[Unit] =
    for {
      _           <- console.println("Starting influxDB projection...")
      _           <- inc.createDb
      offset      <- Offset.load(ic.offsetFile)
      eventStream <- esc(offset)
      stream      = executeStream(eventStream)
      saveOffset  = writeOffsetPeriodically(eventStream)
      _           <- F.race(stream, saveOffset)
    } yield ()

  private def executeStream(eventStream: EventStream[F]): F[Unit] = {
    implicit def logOnError[A] = logRetryErrors[F, A]("fetching SSE")
    def successCondition[A]    = cfg.env.httpClient.retry.condition.notRetryFromEither[A] _
    val compiledStream = eventStream.value
      .through(printConsumedEventSkipFailed(console))
      .flatMap {
        case (ev, org, proj) =>
          val maybeConfig = ic.projects
            .get((org, proj))
            .flatMap(pc =>
              pc.types.collectFirst {
                case tc if ev.resourceTypes.exists(_.toString == tc.tpe) => (pc, tc, ev, org, proj)
              }
            )
          Stream.fromIterator[F](maybeConfig.iterator)
      }
      .evalMap {
        case (pc, tc, ev, org, proj) =>
          val query = tc.query
            .replaceAllLiterally("{resource_id}", ev.resourceId.renderString)
            .replaceAllLiterally("{event_rev}", ev.rev.toString)
          spc.query(org, proj, pc.sparqlView, query).flatMap(res => insert(tc, res, org, proj))
      }
      .through(printEvaluatedProjectionSkipFailed(console))
      .attempt
      .map(_.leftMap(err => Unexpected(Option(err.getMessage).getOrElse("").take(30))).map(_ => ()))
      .compile
      .lastOrError

    compiledStream.retryingM(successCondition) >> F.unit
  }

  private def insert(
      tc: TypeConfig,
      res: Either[ClientError, SparqlResults],
      org: OrgLabel,
      proj: ProjectLabel
  ): F[Either[ClientError, Unit]] =
    res match {
      case Left(err) => F.pure(Left(err))
      case Right(results) =>
        InfluxPoint
          .fromSparqlResults(results, org, proj, tc)
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
