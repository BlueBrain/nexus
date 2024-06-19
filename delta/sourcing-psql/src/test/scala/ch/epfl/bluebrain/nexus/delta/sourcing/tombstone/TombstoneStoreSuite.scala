package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore.Cause
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStoreSuite.{entityType, SimpleResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant

class TombstoneStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobieTruncateAfterTest)

  private lazy val xas = doobieTruncateAfterTest()

  private val id1           = nxv + "id"
  private val originalTypes = Set(nxv + "SimpleResource", nxv + "SimpleResource2", nxv + "SimpleResource3")
  private val originalState = SimpleResource(id1, originalTypes, Instant.EPOCH)

  private def selectCause(id: Iri, tag: Tag) =
    sql"""
         | SELECT cause
         | FROM public.scoped_tombstones
         | WHERE id = $id AND tag = $tag""".stripMargin.query[Cause].option.transact(xas.read)

  private def count = sql"""SELECT count(*) FROM public.scoped_tombstones""".query[Long].unique.transact(xas.read)

  test("Save a tombstone for the given tag") {
    val tag = UserTag.unsafe("v1")
    for {
      _ <- TombstoneStore.save(entityType, originalState, tag).transact(xas.write)
      _ <- selectCause(id1, tag).assertEquals(Some(Cause.deleted))
    } yield ()
  }

  test("Not save a tombstone for a new resource") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, Set(nxv + "SimpleResource2"), Instant.EPOCH)
    for {
      _ <- TombstoneStore.save(entityType, None, newState).transact(xas.write)
      _ <- selectCause(id2, Tag.latest).assertEquals(None)
    } yield ()
  }

  test("Not save a tombstone for a resource when no type has been removed") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, originalTypes + (nxv + "SimpleResource4"), Instant.EPOCH)
    for {
      _ <- TombstoneStore.save(entityType, Some(originalState), newState).transact(xas.write)
      _ <- selectCause(id2, Tag.latest).assertEquals(None)
    } yield ()
  }

  test("Save a tombstone for a resource where types have been removed") {
    val id3      = nxv + "id3"
    val newState = SimpleResource(id3, Set(nxv + "SimpleResource2"), Instant.EPOCH)
    for {
      _ <- TombstoneStore.save(entityType, Some(originalState), newState).transact(xas.write)
      _ <- selectCause(id3, Tag.latest).assertEquals(
             Some(Cause.diff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), None))
           )
    } yield ()
  }

  test("Purge tombstone older than the given instant") {
    def stateAt(instant: Instant) = SimpleResource(nxv + "id", Set(nxv + "SimpleResource2"), instant)
    val threshold                 = Instant.now()
    val toDelete                  = stateAt(threshold.minusMillis(1L))
    val toKeep                    = stateAt(threshold.plusMillis(1L))

    for {
      _ <- TombstoneStore.save(entityType, Some(originalState), toDelete).transact(xas.write)
      _ <- TombstoneStore.save(entityType, Some(originalState), toKeep).transact(xas.write)
      _ <- count.assertEquals(2L)
      _ <- TombstoneStore.deleteExpired(threshold, xas)
      _ <- count.assertEquals(1L)
    } yield ()
  }

}

object TombstoneStoreSuite {

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
