package ch.epfl.bluebrain.nexus.cli.clients

import java.time.Instant
import java.util.regex.Pattern.quote

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.influx.TypeConfig
import ch.epfl.bluebrain.nexus.cli.utils.{Resources, TimeTransformation}
import fs2._
import fs2.text._
import org.http4s.EntityEncoder
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class InfluxPointSpec extends AnyWordSpecLike with Matchers with Resources with Inspectors with TimeTransformation {

  private def writeToString[A](a: A)(implicit W: EntityEncoder[IO, A]): String =
    Stream
      .emit(W.toEntity(a))
      .covary[IO]
      .flatMap(_.body)
      .through(utf8Decode)
      .foldMonoid
      .compile
      .last
      .map(_.getOrElse(""))
      .unsafeRunSync()

  "An InfluxPoint" should {

    val created = Instant.now()
    val updated = created.plusSeconds(5)

    "be created from SparqlResults" in {

      val sparqlResults = jsonContentOf(
        "/templates/sparql-results-influx.json",
        Map(
          quote("{created}") -> created.toString,
          quote("{updated}") -> updated.toString,
          quote("{bytes}")   -> 1234.toString,
          quote("{project}") -> "myorg/myproject"
        )
      ).as[SparqlResults].getOrElse(throw new IllegalArgumentException)

      val typeConfig = TypeConfig("https://neuroshapes.org/Subject", "", "datastats", Set("bytes"), "updated")

      val expected = InfluxPoint(
        "datastats",
        Map("created" -> created.toString, "project" -> "myorg/myproject", "deprecated" -> "false"),
        Map("bytes"   -> "1234"),
        Some(updated)
      )

      InfluxPoint.fromSparqlResults(sparqlResults, typeConfig) shouldEqual
        List(expected)

    }

    "converted to string" in {
      val point      = InfluxPoint(
        "m1",
        Map("created" -> created.toString, "project" -> "org/proj", "deprecated" -> "false"),
        Map("bytes"   -> "1234"),
        Some(updated)
      )
      val pointNoTag = InfluxPoint(
        "m2",
        Map.empty,
        Map("bytes" -> "2345"),
        Some(updated)
      )

      val list = List(
        point      -> s"m1,created=${created.toString},project=org/proj,deprecated=false bytes=1234 ${toNano(updated)}",
        pointNoTag -> s"m2 bytes=2345 ${toNano(updated)}"
      )

      forAll(list) { case (point, str) =>
        writeToString(point) shouldEqual str
      }
    }
  }

}
