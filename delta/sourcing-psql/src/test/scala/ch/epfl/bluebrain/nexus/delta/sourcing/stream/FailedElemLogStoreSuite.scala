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

class FailedElemLogStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = FailedElemLogStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val name     = "errors"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = iri"https://resource"

  private val metadata = ProjectionMetadata("test", name, Some(project), Some(resource))

  private val error = new RuntimeException("boom")
  private val fail1 = FailedElem(EntityType("ACL"), "id", Instant.EPOCH, Offset.At(42L), error)
  private val fail2 = FailedElem(EntityType("Schema"), "id", Instant.EPOCH, Offset.At(42L), error)

  test("Return no entries by name") {
    for {
      entries <- store.entries(name, Offset.At(1L)).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return no entries by (project, id)") {
    for {
      entries <- store.entries(project, resource, Offset.At(1L)).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Insert error") {
    for {
      _       <- store.save(metadata, fail1)
      entries <- store.entries(name, Offset.At(1L)).compile.toList
      r        = entries.assertOneElem
      _        = assertEquals(r.projectionMetadata, metadata)
      _        = assertEquals(r.ordering, Offset.At(1L))
      elem     = r.failedElemData
      _        = assertEquals(elem.offset, Offset.At(42L))
      _        = assertEquals(elem.errorType, "java.lang.RuntimeException")
      _        = assertEquals(elem.id, "id")
      _        = assertEquals(elem.entityType, EntityType("ACL"))
    } yield ()
  }

  test("Insert several errors") {
    val saveErrors =
      store.save(metadata, fail1) >> store.save(metadata, fail2)
    for {
      _       <- saveErrors
      entries <- store.entries(name, Offset.At(1L)).compile.toList
      _        = entries.assertSize(3)
    } yield ()
  }

  test("Return entries by (project, id)") {
    for {
      entries <- store.entries(project, resource, Offset.At(1L)).compile.toList
      _        = entries.assertSize(3)
    } yield ()
  }

  test("Return empty if not found by name") {
    for {
      entries <- store.entries("other", Offset.At(1L)).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Return empty if not found by (project, id)") {
    for {
      entries <- store.entries(project, iri"https://example.com", Offset.At(1L)).compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

}
