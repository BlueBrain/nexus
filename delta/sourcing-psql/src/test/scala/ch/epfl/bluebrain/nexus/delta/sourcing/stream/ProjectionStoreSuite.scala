package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import munit.AnyFixture

import java.time.Instant

class ProjectionStoreSuite extends BioSuite with IOFixedClock with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ProjectionStore(xas, QueryConfig(10, RefreshStrategy.Stop))

  private val name     = "offset"
  private val project  = ProjectRef.unsafe("org", "proj")
  private val resource = iri"https://resource"

  private val metadata = ProjectionMetadata("test", name, Some(project), Some(resource))
  private val progress   = ProjectionProgress(Offset.At(42L), Instant.EPOCH, 5, 2, 1)
  private val newProgress = progress.copy(offset = Offset.At(100L), processed = 100L)

  test("Return an empty offset when not found") {
    store.offset("not found").assertNone
  }

  test("Return no entries") {
    for {
      entries <- store.entries.compile.toList
      _        = entries.assertEmpty()
    } yield ()
  }

  test("Create an offset") {
    for {
      _       <- store.save(metadata, progress)
      _       <- store.offset(name).assertSome(progress)
      entries <- store.entries.compile.toList
      r        = entries.assertOneElem
      _        = assertEquals((r.name, r.project, r.resourceId, r.progress), (name, Some(project), Some(resource), progress))
      _        = assert(r.createdAt == r.updatedAt, "Created and updated at values are not identical after creation")
    } yield ()
  }

  test("Update an offset") {
    val newMetadata = ProjectionMetadata("test", name, None, None)
    for {
      _       <- store.offset(name).assertSome(progress)
      _       <- store.save(newMetadata, newProgress)
      _       <- store.offset(name).assertSome(newProgress)
      entries <- store.entries.compile.toList
      r        = entries.assertOneElem
      _        = assertEquals((r.name, r.project, r.resourceId, r.progress), (name, None, None, newProgress))
    } yield ()
  }

  test("Delete an offset") {
    for {
      _    <- store.offset(name).assertSome(newProgress)
      _       <- store.delete(name)
      entries <- store.entries.compile.toList
      _        = entries.assertEmpty()
      _   <- store.offset(name).assertNone
    } yield ()
  }

}
