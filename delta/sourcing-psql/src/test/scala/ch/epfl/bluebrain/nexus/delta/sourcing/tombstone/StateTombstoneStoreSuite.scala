package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.*
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.StateTombstoneStore.Cause
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.StateTombstoneStoreSuite.{entityType, SimpleResource}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all.*
import munit.AnyFixture

import java.time.Instant

class StateTombstoneStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[?]] = List(doobieTruncateAfterTest)

  private lazy val xas            = doobieTruncateAfterTest()
  private lazy val tombstoneStore = new StateTombstoneStore(xas)

  private val id1           = nxv + "id"
  private val originalTypes = Set(nxv + "SimpleResource", nxv + "SimpleResource2", nxv + "SimpleResource3")
  private val originalState = SimpleResource(id1, originalTypes, Instant.EPOCH)

  private def save(state: SimpleResource, tags: Tag*) =
    tombstoneStore.save(entityType, state, tags.toList).transact(xas.write)

  private def save(originalState: Option[SimpleResource], newState: SimpleResource) =
    tombstoneStore.save(entityType, originalState, newState).transact(xas.write)

  private def getCause(simpleResource: SimpleResource, tag: Tag) =
    tombstoneStore.unsafeGet(simpleResource.project, simpleResource.id, tag).map(_.map(_.cause))

  test("Save a tombstone for the given tag") {
    val tag = UserTag.unsafe("v1")
    save(originalState, tag) >>
      getCause(originalState, tag).assertEquals(Some(Cause.deleted))
  }

  test("Save an individual tombstone for the different tags") {
    val tag  = UserTag.unsafe("v1")
    val tag2 = UserTag.unsafe("v2")
    val tags = List(tag, tag2, Tag.latest)
    save(originalState, tags*) >>
      tags.traverse { t =>
        getCause(originalState, t).assertEquals(Some(Cause.deleted))
      }
  }

  test("Not save a tombstone for a new resource") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, Set(nxv + "SimpleResource2"), Instant.EPOCH)
    save(None, newState) >>
      getCause(newState, Tag.latest).assertEquals(None)
  }

  test("Not save a tombstone for a resource when no type has been removed") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, originalTypes + (nxv + "SimpleResource4"), Instant.EPOCH)
    save(Some(originalState), newState) >>
      getCause(newState, Tag.latest).assertEquals(None)
  }

  test("Save a tombstone for a resource where types have been removed") {
    val id3      = nxv + "id3"
    val newState = SimpleResource(id3, Set(nxv + "SimpleResource2"), Instant.EPOCH)
    save(Some(originalState), newState) >>
      getCause(newState, Tag.latest).assertEquals(
        Some(Cause.diff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), None))
      )
  }

  test("Purge tombstone older than the given instant") {
    def stateAt(instant: Instant) = SimpleResource(nxv + "id", Set(nxv + "SimpleResource2"), instant)
    val threshold                 = Instant.now()
    val toDelete                  = stateAt(threshold.minusMillis(1L))
    val toKeep                    = stateAt(threshold.plusMillis(1L))

    for {
      _ <- save(Some(originalState), toDelete)
      _ <- save(Some(originalState), toKeep)
      _ <- tombstoneStore.count.assertEquals(2L)
      _ <- tombstoneStore.deleteExpired(threshold)
      _ <- tombstoneStore.count.assertEquals(1L)
    } yield ()
  }

}

object StateTombstoneStoreSuite {

  private val entityType = EntityType("simple")

  final private[tombstone] case class SimpleResource(id: Iri, types: Set[Iri], updatedAt: Instant) extends ScopedState {

    override def project: ProjectRef = ProjectRef.unsafe("org", "proj")

    override def schema: ResourceRef = Latest(nxv + "schema")

    override def rev: Int = 0

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedBy: Identity.Subject = Anonymous

  }
}
