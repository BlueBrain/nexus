package ch.epfl.bluebrain.nexus.cli.influxdb.client

import java.time.Instant

import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.TypeConfig
import ch.epfl.bluebrain.nexus.cli.types.SparqlResults.Binding
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
import fs2.Chunk
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityEncoder, MediaType}

import scala.util.Try

/**
  * Class representing InfluxDb point.
  * See  [[https://docs.influxdata.com/influxdb/v1.7/write_protocols/line_protocol_reference/ InfluxDb reference]] for more details.
  *
  * @param measurement InfluxDB measurement name
  * @param tags        InfluxDB tags
  * @param values      InfluxDB values
  * @param timestamp   Optional InfluxDb timestamp
  */
final case class Point(
    measurement: String,
    tags: Map[String, String],
    values: Map[String, String],
    timestamp: Option[Instant] = None
)

object Point {

  implicit def pointEntityEncoder[F[_]]: EntityEncoder[F, Point] =
    EntityEncoder
      .simple[F, Point](`Content-Type`(MediaType.application.`octet-stream`)) { point =>
        val tags =
          if (point.tags.isEmpty) "" else point.tags.toList.map { case (k, v) => s"$k=$v" }.mkString(",", ",", "")
        val values         = point.values.toList.map { case (k, v) => s"$k=$v" }.mkString(",")
        val timestampNanos = point.timestamp.map(t => (t.toEpochMilli * 1000L * 1000L).toString).getOrElse("")
        val entry          = s"${point.measurement}$tags $values $timestampNanos"
        Chunk.array(entry.getBytes("UTF-8"))
      }

  /**
    * Create InfluxDb [[Point]] from [[SparqlResults]].
    *
    * @param results      PARQL query results.
    * @param organization organization
    * @param project      proj
    * @return             [[Point]] created form the [[SparqlResults]].
    */
  def fromSparqlResults(
      results: SparqlResults,
      organization: Label,
      project: Label,
      tc: TypeConfig
  ): List[Point] = {
    def mapToPoint(bindings: Map[String, Binding]): Option[Point] = {
      val values = tc.values.flatMap(value => bindings.get(value).map(value -> _.value)).toMap
      if (values.isEmpty) None
      else {
        val tags = bindings.view
          .filterKeys(key => !tc.values(key) && key != tc.timestamp)
          .mapValues(_.value) ++ Seq(
          "project" -> s"${organization.value}/${project.value}"
        )
        val timestamp =
          bindings.get(tc.timestamp).flatMap(binding => Try(Instant.parse(binding.value)).toOption)
        Some(Point(tc.measurement, tags.toMap, values, timestamp))
      }
    }

    results.results.bindings.flatMap(mapToPoint)
  }
}
