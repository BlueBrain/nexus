package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.bio.ResourceFixture.TaskFixture
import doobie.implicits._
import monix.bio.Task
import munit.AfterEach

trait BlazegraphSlowQueryStoreFixture {
  self: Doobie.Fixture =>
  protected val blazegraphSlowQueryStore = new TaskFixture[BlazegraphSlowQueryStore]("blazegraph-slow-query-store") {
    private lazy val store                                 = BlazegraphSlowQueryStore(doobie())
    override def apply(): BlazegraphSlowQueryStore         = store
    override def afterEach(context: AfterEach): Task[Unit] =
      sql""" TRUNCATE blazegraph_queries""".stripMargin.update.run.transact(doobie().write).void
  }
}
