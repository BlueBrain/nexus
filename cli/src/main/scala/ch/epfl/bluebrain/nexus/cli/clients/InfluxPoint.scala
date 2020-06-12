package ch.epfl.bluebrain.nexus.cli.clients
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.TimeUnit.SECONDS

import ch.epfl.bluebrain.nexus.cli.config.influx.TypeConfig
import fs2.Chunk
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType}

import scala.util.Try

/**
  * Class representing InfluxDb line protocol point.
  * See  [[https://docs.influxdata.com/influxdb/v1.7/write_protocols/line_protocol_reference/ InfluxDb reference]] for more details.
  *
  * @param measurement the influxDB line protocol measurement name
  * @param tags        the influxDB line protocol tags
  * @param values      the influxDB line protocol values
  * @param timestamp   the optional influxDB line protocol timestamp
  */
final case class InfluxPoint(
    measurement: String,
    tags: Map[String, String],
    values: Map[String, String],
    timestamp: Option[Instant] = None
)

object InfluxPoint {

  implicit def influxPointEntityEncoder[F[_]]: EntityEncoder[F, InfluxPoint] =
    EntityEncoder.simple[F, InfluxPoint](`Content-Type`(MediaType.application.`octet-stream`)) { point =>
      val tags           = Option.when(point.tags.nonEmpty)(point.tags.map { case (k, v) => s"$k=$v" }.mkString(",", ",", ""))
      val values         = point.values.toList.map { case (k, v) => s"$k=$v" }.mkString(",")
      val timestampNanos = point.timestamp.fold("")(t => (SECONDS.toNanos(t.getEpochSecond) + t.getNano).toString)
      val entry          = s"${point.measurement}${tags.getOrElse("")} $values $timestampNanos"
      Chunk.bytes(entry.getBytes(StandardCharsets.UTF_8.name()))
    }

  /**
    * Create a series of [[InfluxPoint]] from [[SparqlResults]].
    *
    * @param results SPARQL query results
    */
  def fromSparqlResults(
      results: SparqlResults,
      tc: TypeConfig
  ): List[InfluxPoint] =
    results.results.bindings.flatMap { bindings =>
      val values = tc.values.flatMap(value => bindings.get(value).map(value -> _.value)).toMap
      Option.when(values.nonEmpty) {
        val tags      = bindings.view
          .filterKeys(key => !tc.values(key) && key != tc.timestamp)
          .mapValues(_.value)
        val timestamp = bindings.get(tc.timestamp).flatMap(binding => Try(Instant.parse(binding.value)).toOption)
        InfluxPoint(tc.measurement, tags.toMap, values, timestamp)
      }
    }
}
