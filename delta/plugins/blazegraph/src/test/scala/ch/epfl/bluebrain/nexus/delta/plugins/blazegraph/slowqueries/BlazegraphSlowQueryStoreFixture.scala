package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import doobie.syntax.all.*
import munit.AfterEach
import munit.catseffect.IOFixture

trait BlazegraphSlowQueryStoreFixture {
  self: Doobie.Fixture =>
  protected val blazegraphSlowQueryStore = new IOFixture[SparqlSlowQueryStore]("blazegraph-slow-query-store") {
    private lazy val store                               = SparqlSlowQueryStore(doobie())
    override def apply(): SparqlSlowQueryStore           = store
    override def afterEach(context: AfterEach): IO[Unit] =
      sql""" TRUNCATE blazegraph_queries""".stripMargin.update.run.transact(doobie().write).void
  }
}
