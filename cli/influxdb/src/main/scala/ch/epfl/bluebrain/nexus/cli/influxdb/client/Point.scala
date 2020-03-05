package ch.epfl.bluebrain.nexus.cli.influxdb.client

import java.time.Instant

import fs2.Chunk
import org.http4s.{EntityEncoder, MediaType}
import org.http4s.headers.`Content-Type`

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

}
