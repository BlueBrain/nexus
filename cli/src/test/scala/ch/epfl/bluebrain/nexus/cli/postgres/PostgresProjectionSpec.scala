package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.modules.postgres.PostgresProjection
import doobie.util.transactor.Transactor

//noinspection SqlNoDataSourceInspection
class PostgresProjectionSpec extends AbstractPostgresSpec {

  "A DB" should {
    "project all schemas" in {
      import doobie.implicits._
      (xa: Transactor[IO], proj: PostgresProjection[IO]) =>
        for {
          _     <- proj.run
          count <- sql"select count(id) from schemas;".query[Int].unique.transact(xa)
          _     = count shouldEqual 175
          maxImport <- sql"select id, count(import) from schema_imports group by id order by count desc limit 1;"
                        .query[(String, Int)]
                        .unique
                        .transact(xa)
          (maxImportSchema, maxImportCount) = maxImport
          _                                 = maxImportSchema shouldEqual "https://neuroshapes.org/commons/entity"
          _                                 = maxImportCount shouldEqual 7
        } yield ()
    }
  }
}
