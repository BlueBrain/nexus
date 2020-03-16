package ch.epfl.bluebrain.nexus.cli.influxdb.client

import java.time.Instant
import java.util.regex.Pattern

import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig.TypeConfig
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.scalatest.OptionValues
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PointSpec extends AnyWordSpecLike with Matchers with Fixtures with OptionValues {

  "PointSpec" should {

    "create a Point from SparqlResults" in {

      val created = Instant.now()
      val updated = created.plusSeconds(5)
      val sparqlResults = jsonContentOf(
        "/sparql-results.json",
        Map(
          Pattern.quote("{created}") -> created.toString,
          Pattern.quote("{updated}") -> updated.toString,
          Pattern.quote("{bytes}")   -> 1234.toString
        )
      ).as[SparqlResults].getOrElse(throw new IllegalArgumentException)

      val typeConfig = TypeConfig(
        Uri.unsafeFromString("https://neuroshapes.org/Subject"),
        "",
        "datastats",
        Set("bytes"),
        "updated"
      )

      val expected = Point(
        "datastats",
        Map(
          "created"    -> created.toString,
          "project"    -> "myorg/myproject",
          "deprecated" -> "false"
        ),
        Map(
          "bytes" -> "1234"
        ),
        Some(updated)
      )

      Point.fromSparqlResults(sparqlResults, Label("myorg"), Label("myproject"), typeConfig) shouldEqual List(
        expected
      )

    }
  }

}
