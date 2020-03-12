package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.IO
import doobie.util.transactor.Transactor

class PostgresProjectionSpec extends AbstractPostgresSpec {

  "A DB" should {
    "select 1" in {
      import doobie.implicits._
      xa: Transactor[IO] =>
        for {
          result <- sql"select 1;".query[Int].unique.transact(xa)
          _      = assert(result == 1)
        } yield ()
    }
  }

}
