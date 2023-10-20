package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.BlazegraphSlowQueryStoreSuite._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import munit.AnyFixture

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import scala.concurrent.duration.DurationInt

class BlazegraphSlowQueryStoreSuite
    extends BioSuite
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
      Instant.now().truncatedTo(ChronoUnit.MILLIS),
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

  private val Now          = Instant.now().truncatedTo(ChronoUnit.MILLIS)
  private val OneWeekAgo   = Now.minus(Duration.ofDays(7))
  private val EightDaysAgo = Now.minus(Duration.ofDays(8))
  private val RecentQuery  = queryAtTime(Now)
  private val OldQuery     = queryAtTime(EightDaysAgo)
}
