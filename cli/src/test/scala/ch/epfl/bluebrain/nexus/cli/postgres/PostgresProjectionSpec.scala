package ch.epfl.bluebrain.nexus.cli.postgres

import java.time.OffsetDateTime

import cats.effect.{Blocker, IO}
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresProjection
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresProjection.TimeMeta.javatime._
import ch.epfl.bluebrain.nexus.cli.sse.Offset
import doobie.util.transactor.Transactor
import fs2.io

//noinspection SqlNoDataSourceInspection
class PostgresProjectionSpec extends AbstractPostgresSpec {

  "A PostgresProjection" should {
    "project all schemas" in {
      import doobie.implicits._
      (xa: Transactor[IO], proj: PostgresProjection[IO]) =>
        for {
          _                                <- proj.run
          count                            <- sql"select count(id) from schemas;".query[Int].unique.transact(xa)
          _                                 = count shouldEqual 175
          maxImport                        <- sql"select id, count(import) from schema_imports group by id order by count desc limit 1;"
                                                .query[(String, Int)]
                                                .unique
                                                .transact(xa)
          (maxImportSchema, maxImportCount) = maxImport
          _                                 = maxImportSchema shouldEqual "https://neuroshapes.org/commons/entity"
          _                                 = maxImportCount shouldEqual 7
          lastUpdated                      <- sql"select last_updated from schemas where id = 'https://neuroshapes.org/commons/entity'"
                                                .query[OffsetDateTime]
                                                .unique
                                                .transact(xa)
          _                                 = lastUpdated.toInstant.toEpochMilli shouldEqual 1584615316089L
        } yield ()
    }
    "save offset" in { (cfg: AppConfig, blocker: Blocker, proj: PostgresProjection[IO], console: Console[IO]) =>
      implicit val b: Blocker     = blocker
      implicit val c: Console[IO] = console

      for {
        _      <- proj.run
        exists <- io.file.exists[IO](blocker, cfg.postgres.offsetFile)
        _       = exists shouldEqual true
        offset <- Offset.load(cfg.postgres.offsetFile)
        _       = offset.nonEmpty shouldEqual true
      } yield ()
    }
  }
}
