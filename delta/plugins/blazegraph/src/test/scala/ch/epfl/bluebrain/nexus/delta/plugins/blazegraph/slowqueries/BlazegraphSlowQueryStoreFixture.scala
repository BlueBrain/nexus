package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.ce.ResourceFixture.IOFixture
import doobie.implicits._
import munit.AfterEach

trait BlazegraphSlowQueryStoreFixture {
  self: Doobie.Fixture =>
  protected val blazegraphSlowQueryStore = new IOFixture[BlazegraphSlowQueryStore]("blazegraph-slow-query-store") {
    private lazy val store                               = BlazegraphSlowQueryStore(doobie())
    override def apply(): BlazegraphSlowQueryStore       = store
    override def afterEach(context: AfterEach): IO[Unit] =
      sql""" TRUNCATE blazegraph_queries""".stripMargin.update.run.transact(doobie().write).void
  }
}
