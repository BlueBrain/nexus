package ch.epfl.bluebrain.nexus.cli.influxdb

import java.nio.file.Path

import cats.effect.{Blocker, Concurrent, ContextShift, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.EventStreamClient.EventStream
import ch.epfl.bluebrain.nexus.cli.influxdb.client.{InfluxDbClient, Point}
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.{ProjectConfig, TypeConfig}
import ch.epfl.bluebrain.nexus.cli.types.{Event, Offset, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.{Console, EventStreamClient, SparqlClient}
import fs2.{io, text, Stream}

import scala.concurrent.duration._

/**
  * Indexer that pushes points into InfluxDb based on a SPARQL query.
  */
class InfluxDbIndexer[F[_]: ContextShift](
    eventStreamClient: EventStreamClient[F],
    sparqlClient: SparqlClient[F],
    influxDbClient: InfluxDbClient[F],
    console: Console[F],
    config: InfluxDbConfig
)(implicit F: Concurrent[F], timer: Timer[F], blocker: Blocker) {

  private def readOffset(file: Path): F[Option[Offset]] =
    io.file
      .readAll(file, blocker, 1024)
      .through(text.utf8Decode)
      .through(text.lines)
      .compile
      .string
      .map(Offset(_))

  private def writeOffset(offset: Offset): F[Unit] =
    io.file.createDirectories(blocker, config.indexing.offsetFile.getParent) >>
      io.file.deleteIfExists(blocker, config.indexing.offsetFile) >>
      Stream(offset.asString)
        .through(text.utf8Encode)
        .through(io.file.writeAll(config.indexing.offsetFile, blocker))
        .compile
        .drain

  private def repeatAtFixedRate(period: FiniteDuration, task: F[Unit]): F[Unit] =
    task >> timer.sleep(period) >> repeatAtFixedRate(period, task)

  /**
    * Index points into InfluxDB based on a SPARQL query.

    * @param restart whether to restart from the beginning.
    */
  def index(restart: Boolean): F[Unit] = {

    def getOffset(): F[Option[Offset]] = {
      if (restart) {
        io.file.deleteIfExists(blocker, config.indexing.offsetFile).as(None)
      } else {
        io.file.exists(blocker, config.indexing.offsetFile).flatMap {
          case false => F.pure(None)
          case true  => readOffset(config.indexing.offsetFile)
        }

      }
    }

    def writeOffsetPeriodically(sseStream: EventStream[F]): F[Unit] = {
      repeatAtFixedRate(
        config.indexing.offsetSaveInterval,
        sseStream.currentEventId().flatMap {
          case Some(offset) =>
            writeOffset(offset)
          case None =>
            F.unit
        }
      )
    }

    def executeStream(sseStream: EventStream[F]): F[Unit] =
      sseStream.value
        .flatMap { event =>
          config.data.configOf((event.organization, event.project)) match {
            case None => Stream.empty
            case Some(pc) =>
              Stream.emits(
                pc.findTypes(event.resourceTypes).map((event, _, pc))
              )
          }
        }
        .mapAsync(config.indexing.sparqlConcurrency) {
          case (event, typeConf, pc) =>
            sparqlClient
              .query(
                event.organization,
                event.project,
                pc.sparqlView,
                SparqlQueryTemplate(typeConf.query).inject(event.resourceId)
              )
              .flatMap[Option[(Event, SparqlResults, ProjectConfig, TypeConfig)]] {
                case Left(err)  => console.printlnErr(err.show).as(None)
                case Right(res) => F.pure(Some((event, res, pc, typeConf)))
              }
        }
        .flatMap {
          case Some((event, results, pc, tc)) =>
            Stream.emits(
              Point.fromSparqlResults(results, event.organization, event.project, tc).map((_, pc))
            )
          case None => Stream.empty
        }
        .mapAsync(config.indexing.influxdbConcurrency) {
          case (point, pc) =>
            influxDbClient.write(pc.database, point)
        }
        .evalScan(0L) {
          case (idx, Right(_)) if idx % 100 == 0 && idx != 0 =>
            console.println(s"Processed $idx events.") >>
              F.pure(idx + 1)
          case (idx, Left(err)) => console.printlnErr(err.show) >> F.pure(idx + 1)
          case (idx, _)         => F.pure(idx + 1)
        }
        .compile
        .drain

    def createDbs() =
      config.data.projects.values
        .map { pc => influxDbClient.createDb(pc.database) }
        .toList
        .sequence

    for {
      offset <- getOffset()
      _      <- createDbs()
      stream <- eventStreamClient(offset)
      write  = writeOffsetPeriodically(stream)
      exec   = executeStream(stream)
      _      <- F.race(write, exec)
    } yield ()
  }

}

object InfluxDbIndexer {

  /**
    * Create an instance of [[InfluxDbIndexer]].
    */
  def apply[F[_]: Concurrent: Timer: ContextShift](
      eventStreamClient: EventStreamClient[F],
      sparqlClient: SparqlClient[F],
      influxDbClient: InfluxDbClient[F],
      console: Console[F],
      config: InfluxDbConfig
  )(implicit blocker: Blocker): InfluxDbIndexer[F] =
    new InfluxDbIndexer[F](eventStreamClient, sparqlClient, influxDbClient, console, config)
}
