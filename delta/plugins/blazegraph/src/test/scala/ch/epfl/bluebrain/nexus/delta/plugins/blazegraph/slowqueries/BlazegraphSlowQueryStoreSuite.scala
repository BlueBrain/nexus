package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class BlazegraphSlowQueryStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {
  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas   = doobie()
  private lazy val store = BlazegraphSlowQueryStore(xas)

  test("Save a slow query") {

    val view      = ViewRef(ProjectRef.unsafe("epfl", "blue-brain"), Iri.unsafe("brain"))
    val slowQuery = BlazegraphSlowQuery(
      view,
      SparqlQuery(""),
      wasError = true,
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
}
