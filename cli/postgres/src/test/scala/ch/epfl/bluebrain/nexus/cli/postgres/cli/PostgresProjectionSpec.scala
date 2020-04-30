package ch.epfl.bluebrain.nexus.cli.postgres.cli

import cats.effect.IO
import ch.epfl.bluebrain.nexus.cli.SparqlClient
import ch.epfl.bluebrain.nexus.cli.postgres.AbstractPostgresSpec
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
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

    "return the right sparql query result" in { sc: SparqlClient[IO] =>
      for {
        results <- sc.query(
                    Label("tutorialnexus"),
                    Label("datamodels"),
                    """PREFIX nxv:<https://bluebrain.github.io/nexus/vocabulary/>
                      |
                      |SELECT ?s
                      |WHERE {
                      |  ?s a nxv:Schema .
                      |}
                      |""".stripMargin
                  )
        _ <- IO.delay(
              results
                .getOrElse(SparqlResults.empty)
                .results
                .bindings
                .flatMap(_.get("s").toList)
                .map(_.value)
                .foreach(println)
            )
      } yield ()
    }
  }

}
