package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
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
  private lazy val store = BlazegraphSlowQueryStore(xas, true).runSyncUnsafe()

  test("Return an empty offset when not found") {
    store
      .save(
        BlazegraphSlowQuery(
          Iri.unsafe("brain"),
          ProjectRef.unsafe("epfl", "blue-brain"),
          SparqlQuery(""),
          1.second,
          Instant.now(),
          Identity.User("Ted Lasso", Label.unsafe("epfl"))
        )
      )
      .assert(())
  }
}
