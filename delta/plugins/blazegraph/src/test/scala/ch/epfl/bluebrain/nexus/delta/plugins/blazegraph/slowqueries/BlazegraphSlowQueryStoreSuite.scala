package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import munit.AnyFixture

import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt
import BlazegraphSlowQueryStoreSuite._

class BlazegraphSlowQueryStoreSuite
    extends BioSuite
    with IOFixedClock
    with Doobie.Fixture
    with Doobie.Assertions
    with BlazegraphSlowQueryStoreFixture {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, blazegraphSlowQueryStore)

  private lazy val store = blazegraphSlowQueryStore()

  test("Save a slow query") {

    val slowQuery = BlazegraphSlowQuery(
      view,
      SparqlQuery(""),
      failed = true,
      1.second,
      Instant.now(),
      Identity.User("Ted Lasso", Label.unsafe("epfl"))
    )

    for {
      _      <- store.save(slowQuery)
      lookup <- store.listForTestingOnly(view)
    } yield {
      assertEquals(lookup, List(slowQuery))
    }
  }

  test("Remove old queries") {
    for {
      _       <- store.save(OldQuery)
      _       <- store.save(RecentQuery)
      _       <- store.removeQueriesOlderThan(OneWeekAgo)
      results <- store.listForTestingOnly(view)
    } yield {
      assert(results.contains(RecentQuery), "recent query was deleted")
      assert(!results.contains(OldQuery), "old query was not deleted")
    }
  }
}

object BlazegraphSlowQueryStoreSuite {
  private val view = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("brain"))
  private def queryAtTime(instant: Instant): BlazegraphSlowQuery = {
    BlazegraphSlowQuery(
      view,
      SparqlQuery(""),
      failed = false,
      1.second,
      instant,
      Identity.User("Ted Lasso", Label.unsafe("epfl"))
    )
  }

  private val Now          = Instant.now()
  private val OneWeekAgo   = Now.minus(Duration.ofDays(7))
  private val EightDaysAgo = Now.minus(Duration.ofDays(8))
  private val RecentQuery  = queryAtTime(Now)
  private val OldQuery     = queryAtTime(EightDaysAgo)
}
