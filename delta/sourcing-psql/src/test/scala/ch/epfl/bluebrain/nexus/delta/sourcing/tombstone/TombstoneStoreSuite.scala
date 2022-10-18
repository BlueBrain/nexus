package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore.StateDiff
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStoreSuite.{entityType, SimpleResource}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import io.circe.Json
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant

class TombstoneStoreSuite extends BioSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val state = SimpleResource(
    Set(nxv + "SimpleResource", nxv + "SimpleResource2", nxv + "SimpleResource3"),
    Latest(nxv + "schema")
  )

  private def select(id: String, tag: Tag) =
    sql"""
         | SELECT diff
         | FROM public.scoped_tombstones
         | WHERE id = $id AND tag = $tag""".stripMargin.query[Json].option.transact(xas.read)

  private def selectAsDiff(id: String, tag: Tag) =
    select(id, tag).flatMap {
      case None       => Task.none
      case Some(json) => Task.fromEither(json.as[StateDiff]).map(Some(_))
    }

  test("Save a tombstone for the given tag") {
    val tag = UserTag.unsafe("v1")
    for {
      _ <- TombstoneStore.save(entityType, "id", state, tag).transact(xas.write).assert(())
      _ <- select("id", tag).assertSome(Json.obj())
    } yield ()
  }

  test("Not save a tombstone for a new resource") {
    val newState = SimpleResource(
      Set(nxv + "SimpleResource2"),
      state.schema
    )
    for {
      _ <- TombstoneStore
             .save(entityType, "id2", None, newState)
             .transact(xas.write)
             .assert(())
      _ <- select("id2", Tag.latest).assertNone
    } yield ()
  }

  test("Not save a tombstone for a resource when no type has been removed and schema remains the same") {
    val newState = SimpleResource(
      state.types + (nxv + "SimpleResource4"),
      state.schema
    )
    for {
      _ <- TombstoneStore
             .save(entityType, "id2", Some(state), newState)
             .transact(xas.write)
             .assert(())
      _ <- select("id2", Tag.latest).assertNone
    } yield ()
  }

  test("Save a tombstone for a resource where types have been removed and schema remains the same") {
    val newState = SimpleResource(
      Set(nxv + "SimpleResource2"),
      Latest(nxv + "schema")
    )
    for {
      _ <- TombstoneStore.save(entityType, "id3", Some(state), newState).transact(xas.write).assert(())

      _ <- selectAsDiff("id3", Tag.latest).assertSome(
             StateDiff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), None)
           )
    } yield ()
  }

  test("Save a tombstone for a resource where no type has been removed and schema changed") {
    val newState = SimpleResource(
      state.types,
      Latest(nxv + "schema2")
    )
    for {
      _ <- TombstoneStore.save(entityType, "id4", Some(state), newState).transact(xas.write).assert(())

      _ <- selectAsDiff("id4", Tag.latest).assertSome(StateDiff(Set.empty, Some(state.schema)))
    } yield ()
  }

  test("Save a tombstone for a resource where types have been removed and schema changed") {
    val newState = SimpleResource(
      Set(nxv + "SimpleResource2"),
      Latest(nxv + "schema2")
    )
    for {
      _ <- TombstoneStore.save(entityType, "id5", Some(state), newState).transact(xas.write).assert(())

      _ <- selectAsDiff("id5", Tag.latest).assertSome(
             StateDiff(Set(nxv + "SimpleResource", nxv + "SimpleResource3"), Some(state.schema))
           )
    } yield ()
  }

}

object TombstoneStoreSuite {

  private val entityType = EntityType("simple")

  final private[tombstone] case class SimpleResource(types: Set[Iri], schema: ResourceRef) extends ScopedState {

    override def project: ProjectRef = ProjectRef.unsafe("org", "proj")

    override def rev: Int = 0

    override def deprecated: Boolean = false

    override def createdAt: Instant = Instant.EPOCH

    override def createdBy: Identity.Subject = Anonymous

    override def updatedAt: Instant = Instant.EPOCH

    override def updatedBy: Identity.Subject = Anonymous

  }
}
