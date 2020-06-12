package ch.epfl.bluebrain.nexus.cli.influx

import java.nio.file.Files

import cats.effect.{Blocker, IO}
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.clients.InfluxClient
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.modules.influx.InfluxProjection
import ch.epfl.bluebrain.nexus.cli.sse.Offset
import fs2.io

//noinspection SqlNoDataSourceInspection
class InfluxProjectionSpec extends AbstractInfluxSpec {

  "A InfluxProjection" should {
    val brainParcelationExpected = jsonContentOf("/templates/influxdb-results-brain-parcelation.json")
    val cellRecordExpected       = jsonContentOf("/templates/influxdb-results-cell-record.json")
    "project distribution sizes" in { (proj: InfluxProjection[IO], client: InfluxClient[IO]) =>
      for {
        _                <- proj.run
        brainQueryResult <- client.query("""SELECT * FROM "brainParcelation"""")
        cellQueryResult  <- client.query("""SELECT * FROM "cellRecord"""")
        _                 = brainQueryResult shouldEqual Right(brainParcelationExpected)
        _                 = cellQueryResult shouldEqual Right(cellRecordExpected)
      } yield ()
    }
    "save offset" in { (cfg: AppConfig, blocker: Blocker, proj: InfluxProjection[IO], console: Console[IO]) =>
      implicit val b: Blocker     = blocker
      implicit val c: Console[IO] = console
      for {
        _      <- proj.run
        exists <- io.file.exists[IO](blocker, cfg.influx.offsetFile)
        _       = exists shouldEqual true
        _       = println(s"Offset file content '${Files.readString(cfg.influx.offsetFile)}'")
        offset <- Offset.load(cfg.influx.offsetFile)
        _       = offset.nonEmpty shouldEqual true
      } yield ()
    }
  }
}
