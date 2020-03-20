package ch.epfl.bluebrain.nexus.cli.influxdb

import java.nio.file.Paths
import java.time.Instant

import cats.effect.IO
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.cli.Console.LiveConsole
import ch.epfl.bluebrain.nexus.cli.influxdb.client.{InfluxDbClient, Point}
import ch.epfl.bluebrain.nexus.cli.influxdb.config.InfluxDbConfig
import ch.epfl.bluebrain.nexus.cli.utils.Fixtures
import org.http4s.Method._
import org.http4s.client.Client
import org.http4s.{HttpApp, Response, Status, Uri}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class InfluxDbClientSpec extends AnyWordSpecLike with Matchers with Fixtures with OptionValues {

  "InfluxDbClient" should {

    val config: InfluxDbConfig =
      InfluxDbConfig(Paths.get(getClass().getResource("/influxdb-test.conf").toURI())).toOption.value

    val ref = Ref.of[IO, Seq[String]](Vector.empty).unsafeRunSync()

    val mockedHttpApp = HttpApp[IO] {
      case r if r.uri == Uri.unsafeFromString("http://localhost:8086/query") && r.method == POST =>
        IO.pure(Response[IO](Status.NoContent))
      case r if r.uri == Uri.unsafeFromString("http://localhost:8086/write?db=nstats") && r.method == POST =>
        for {
          body <- r.bodyAsText.compile.string
          _    <- ref.update(_ :+ body)
        } yield Response[IO](Status.NoContent)
      case _ => ???

    }

    val mockedHttpClient: Client[IO] = Client.fromHttpApp(mockedHttpApp)

    val client = InfluxDbClient(mockedHttpClient, new LiveConsole[IO](), config.client)

    "create database" in {
      client.createDb("testDb").unsafeRunSync() shouldEqual ()
    }

    "write points" in {
      val instant1 = Instant.now()
      val instant2 = instant1.plusSeconds(60)

      val points = List(
        Point("measurement1", Map("tag"               -> "tagValue"), Map("value" -> "1234"), Some(instant1)),
        Point("measurement2", Map.empty, Map("value1" -> "1234", "value2"         -> "5678"), Some(instant2)),
        Point(
          "measurement3",
          Map("tag1"   -> "tagValue1", "tag2" -> "tagValue2"),
          Map("value1" -> "1234", "value2"    -> "5678"),
          None
        )
      )

      points.foreach(
        client.write("nstats", _).unsafeRunSync() shouldEqual Right(())
      )

      ref.get.unsafeRunSync() shouldEqual Seq(
        s"measurement1,tag=tagValue value=1234 ${instant1.toEpochMilli * 1000L * 1000L}",
        s"measurement2 value1=1234,value2=5678 ${instant2.toEpochMilli * 1000L * 1000L}",
        s"measurement3,tag1=tagValue1,tag2=tagValue2 value1=1234,value2=5678 "
      )

    }
  }

}
