package ch.epfl.bluebrain.nexus.cli.influxdb

import cats.effect.{Concurrent, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.influxdb.client.{InfluxDbClient, Point}
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.types.EventEnvelope
import ch.epfl.bluebrain.nexus.cli.{EventStreamClient, SparqlClient}
import fs2.Stream

/**
  * Indexer that pushes points into InfluxDb based on a SPARQL query.
  */
class InfluxDbIndexer[F[_]: Timer](
    eventStreamClient: EventStreamClient[F],
    sparqlClient: SparqlClient[F],
    influxDbClient: InfluxDbClient[F],
    config: InfluxDbConfig
)(implicit F: Concurrent[F]) {

  /**
    * Index points into InfluxDB base on a SPARQL query.
    */
  def index(): F[Unit] = {
    def executeStream(sseStream: Stream[F, EventEnvelope]): F[Unit] =
      sseStream
        .mapFilter { ee =>
          for {
            pc <- config.data.configOf((ee.event.organization, ee.event.project))
            qt <- pc.findTemplate(ee.event.resourceTypes)

          } yield (ee, qt, pc)
        }
        .mapAsync(config.indexing.sparqlConcurrency) {
          case (ee, (tpe, template), pc) =>
            sparqlClient
              .query(ee.event.organization, ee.event.project, pc.sparqlView, template.inject(ee.event.resourceId))
              .map { resp =>
                resp.toOption.map(res => (ee, (tpe, res), pc))
              }

        }
        .mapFilter(r => r)
        .flatMap {
          case (ee, (tpe, results), pc) =>
            Stream.emits(
              Point.fromSparqlResults(results, ee.event.organization, ee.event.project, tpe, pc).map((ee.offset, _, pc))
            )
        }
        .mapAsync(config.indexing.influxdbConcurrency) {
          case (offset, point, pc) =>
            influxDbClient.write(pc.influxdbDatabase, point).as(offset)
        }
        .compile
        .drain

    def createDbs() =
      config.data.projects.values.map(pc => influxDbClient.createDb(pc.influxdbDatabase)).toList.sequence

    for {
      _ <- createDbs()
      _ <- executeStream(eventStreamClient(None))
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
      config: InfluxDbConfig
  ): InfluxDbIndexer[F] = new InfluxDbIndexer[F](eventStreamClient, sparqlClient, influxDbClient, config)
}
