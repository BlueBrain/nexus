package ch.epfl.bluebrain.nexus.cli.influxdb

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.influxdb.client.{InfluxDbClient, Point}
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.{ProjectConfig, TypeConfig}
import ch.epfl.bluebrain.nexus.cli.types.{Event, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.{Console, EventStreamClient, SparqlClient}
import fs2.Stream

/**
  * Indexer that pushes points into InfluxDb based on a SPARQL query.
  */
class InfluxDbIndexer[F[_]: Timer](
    eventStreamClient: EventStreamClient[F],
    sparqlClient: SparqlClient[F],
    influxDbClient: InfluxDbClient[F],
    console: Console[F],
    config: InfluxDbConfig
)(implicit F: Concurrent[F]) {

  /**
    * Index points into InfluxDB base on a SPARQL query.
    */
  def index(): F[Unit] = {

    def executeStream(sseStream: Stream[F, Event]): F[Unit] =
      sseStream
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
        .mapFilter(r => r)
        .flatMap {
          case (event, results, pc, tc) =>
            Stream.emits(
              Point.fromSparqlResults(results, event.organization, event.project, tc).map((_, pc))
            )
        }
        .mapAsync(config.indexing.influxdbConcurrency) {
          case (point, pc) =>
            influxDbClient.write(pc.database, point)
        }
        .map {
          case Right(_)  => ()
          case Left(err) => console.printlnErr(err.show)
        }
        .compile
        .drain

    def createDbs() =
      config.data.projects.values
        .map { pc =>
          influxDbClient.createDb(pc.database)
        }
        .toList
        .sequence

    for {
      _      <- createDbs()
      stream <- eventStreamClient(None)
      _      <- executeStream(stream.value)
    } yield ()
  }

}

object InfluxDbIndexer {

  /**
    * Create an instance of [[InfluxDbIndexer]].
    */
  def apply[F[_]: Concurrent: Timer](
      eventStreamClient: EventStreamClient[F],
      sparqlClient: SparqlClient[F],
      influxDbClient: InfluxDbClient[F],
      console: Console[F],
      config: InfluxDbConfig
  ): InfluxDbIndexer[F] = new InfluxDbIndexer[F](eventStreamClient, sparqlClient, influxDbClient, console, config)
}
