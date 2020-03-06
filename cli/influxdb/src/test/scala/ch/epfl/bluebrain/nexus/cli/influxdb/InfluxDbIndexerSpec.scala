package ch.epfl.bluebrain.nexus.cli.influxdb

import java.time.Instant
import java.util.regex.Pattern

import cats.effect.concurrent.Ref
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.cli.EventStreamClient.TestEventStreamClient
import ch.epfl.bluebrain.nexus.cli.SparqlClient.TestSparqlClient
import ch.epfl.bluebrain.nexus.cli.influxdb.client.InfluxDbClient.TestInfluxDbClient
import ch.epfl.bluebrain.nexus.cli.influxdb.client.Point
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig._
import ch.epfl.bluebrain.nexus.cli.types._
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import com.typesafe.config.ConfigFactory
import org.http4s._
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pureconfig.ConfigSource
import pureconfig.generic.auto._

class InfluxDbIndexerSpec extends AnyWordSpecLike with Matchers with Fixtures with EitherValues {

  "InfluxDb indexer" should {

    "index resources into InfluxDB" in {

      implicit val timer: Timer[IO] = IO.timer(scala.concurrent.ExecutionContext.global)

      val config: InfluxDbConfig = ConfigSource
        .fromConfig(ConfigFactory.parseResources("test-app.conf"))
        .at("influxdb")
        .loadOrThrow[InfluxDbConfig]

      val resourceId1 = Uri.unsafeFromString("https://example.com/v1/myId1")
      val resourceId2 = Uri.unsafeFromString("https://example.com/v1/myId2")

      val bytes1 = 1234
      val bytes2 = 5678

      val instant1 = Instant.now()
      val instant2 = instant1.plusSeconds(10L)
      val instant3 = instant1.plusSeconds(20L)

      val orgLabel      = Label("myorg")
      val projectLabel1 = Label("project1")
      val projectLabel2 = Label("project2")
      val projectLabel3 = Label("project3")
      val events = List(
        Event("Created", resourceId1, orgLabel, projectLabel1, Set(nxv / "TypeA", nxv / "Subject"), instant1),
        Event("Updated", resourceId2, orgLabel, projectLabel2, Set(nxv / "Subject"), instant2),
        Event("Created", resourceId1, orgLabel, projectLabel3, Set(nxv / "TypeA", nxv / "Subject"), instant3)
      )

      val eventStreamClient = new TestEventStreamClient[IO](events)

      val sparqlResults1 = jsonContentOf(
        "/sparql-results.json",
        Map(
          Pattern.quote("{created}") -> instant1.toString,
          Pattern.quote("{updated}") -> instant1.toString,
          Pattern.quote("{bytes}")   -> bytes1.toString
        )
      ).as[SparqlResults].getOrElse(throw new IllegalArgumentException)

      val sparqlResults2 = jsonContentOf(
        "/sparql-results.json",
        Map(
          Pattern.quote("{created}") -> instant1.toString,
          Pattern.quote("{updated}") -> instant2.toString,
          Pattern.quote("{bytes}")   -> bytes2.toString
        )
      ).as[SparqlResults].getOrElse(throw new IllegalArgumentException)

      val sparqlClient = new TestSparqlClient[IO](
        Map(
          (Label("myorg"), Label("project1")) -> sparqlResults1,
          (Label("myorg"), Label("project2")) -> sparqlResults2
        )
      )

      val databases      = Ref[IO].of(Set.empty[String]).unsafeRunSync()
      val points         = Ref[IO].of(Map.empty[String, Vector[Point]]).unsafeRunSync()
      val influxDbClient = new TestInfluxDbClient[IO](databases, points)

      val influxDbIndexer = InfluxDbIndexer[IO](eventStreamClient, sparqlClient, influxDbClient, config)

      val expected = Map(
        "nstats1" -> Vector(
          Point(
            "nstats",
            Map(
              "created"    -> instant1.toString,
              "deprecated" -> "false",
              "project"    -> "myorg/project1",
              "type"       -> "Subject"
            ),
            Map("bytes" -> "1234"),
            Some(instant1)
          )
        ),
        "nstats2" -> Vector(
          Point(
            "nstats",
            Map(
              "created"    -> instant1.toString,
              "deprecated" -> "false",
              "project"    -> "myorg/project2",
              "type"       -> "Subject"
            ),
            Map("bytes" -> "5678"),
            Some(instant2)
          )
        )
      )

      influxDbIndexer.index().unsafeRunSync()

      points.get.unsafeRunSync() shouldEqual expected
    }

  }

}
