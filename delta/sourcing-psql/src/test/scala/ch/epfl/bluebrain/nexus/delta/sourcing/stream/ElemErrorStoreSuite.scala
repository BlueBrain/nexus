package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.FailedElem
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant

class ElemErrorStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ElemErrorStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val name     = "errors"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = iri"https://resource"

  private val metadata = ProjectionMetadata("test", name, Some(project), Some(resource))

  private val error = new RuntimeException("boom")
  private val fail1 = FailedElem(EntityType("ACL"), "id", Instant.EPOCH, Offset.At(42L), error)

  test("Insert errors") {
    for {
      _       <- store.save(metadata, fail1)
      entries <- store.entries(name, Offset.At(1L)).compile.toList
      _        = entries.assertSize(1)
    } yield ()
  }

}
