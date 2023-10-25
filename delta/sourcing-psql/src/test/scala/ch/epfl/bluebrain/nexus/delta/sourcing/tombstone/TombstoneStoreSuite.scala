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
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import doobie.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant

class TombstoneStoreSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val id1   = nxv + "id"
  private val state = SimpleResource(
    id1,
    Set(nxv + "SimpleResource", nxv + "SimpleResource2", nxv + "SimpleResource3"),
    Latest(nxv + "schema")
  )

  private def select(id: Iri, tag: Tag) =
    sql"""
         | SELECT cause
         | FROM public.scoped_tombstones
         | WHERE id = $id AND tag = $tag""".stripMargin.query[Json].option.transact(xas.read)

  private def selectAsCause(id: Iri, tag: Tag) =
    select(id, tag).flatMap {
      case None       => Task.none
      case Some(json) => Task.fromEither(json.as[Cause]).map(Some(_))
    }

  test("Save a tombstone for the given tag") {
    val tag = UserTag.unsafe("v1")
    for {
      _ <- TombstoneStore.save(entityType, state, tag).transact(xas.write).assert(())
      _ <- select(id1, tag).assertSome(Cause.deleted.asJson)
    } yield ()
  }

  test("Not save a tombstone for a new resource") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, Set(nxv + "SimpleResource2"), state.schema)
    for {
      _ <- TombstoneStore
             .save(entityType, None, newState)
             .transact(xas.write)
             .assert(())
      _ <- select(id2, Tag.latest).assertNone
    } yield ()
  }

  test("Not save a tombstone for a resource when no type has been removed and schema remains the same") {
    val id2      = nxv + "id2"
    val newState = SimpleResource(id2, state.types + (nxv + "SimpleResource4"), state.schema)
    for {
      _ <- TombstoneStore
             .save(entityType, Some(state), newState)
             .transact(xas.write)
             .assert(())
      _ <- select(id2, Tag.latest).assertNone
    } yield ()
  }

  test("Save a tombstone for a resource where types have been removed and schema remains the same") {
    val id3      = nxv + "id3"
    val newState = SimpleResource(id3, Set(nxv + "SimpleResource2"), Latest(nxv + "schema"))
    for {
      _ <- TombstoneStore.save(entityType, Some(state), newState).transact(xas.write).assert(())
      _ <- selectAsCause(id3, Tag.latest).assertSome(
             Cause.diff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), None)
           )
    } yield ()
  }

  test("Save a tombstone for a resource where no type has been removed and schema changed") {
    val id4      = nxv + "id4"
    val newState = SimpleResource(id4, state.types, Latest(nxv + "schema2"))
    for {
      _ <- TombstoneStore.save(entityType, Some(state), newState).transact(xas.write).assert(())
      _ <- selectAsCause(id4, Tag.latest).assertSome(Cause.diff(Set.empty, Some(state.schema)))
    } yield ()
  }

  test("Save a tombstone for a resource where types have been removed and schema changed") {
    val id5      = nxv + "id5"
    val newState = SimpleResource(id5, Set(nxv + "SimpleResource2"), Latest(nxv + "schema2"))
    for {
      _ <- TombstoneStore.save(entityType, Some(state), newState).transact(xas.write).assert(())
      _ <- selectAsCause(id5, Tag.latest).assertSome(
             Cause.diff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), Some(state.schema))
           )
    } yield ()
  }

}

object TombstoneStoreSuite {

  private val entityType = EntityType("simple")

  final private[tombstone] case class SimpleResource(id: Iri, types: Set[Iri], schema: ResourceRef)
      extends ScopedState {

    override def project: ProjectRef = ProjectRef.unsafe("org", "proj")

    override def rev: Int = 0

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedAt: Instant = Instant.EPOCH

    override def updatedBy: Identity.Subject = Anonymous

  }
}
