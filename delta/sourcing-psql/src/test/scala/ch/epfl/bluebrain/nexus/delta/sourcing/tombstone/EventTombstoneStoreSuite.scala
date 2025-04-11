package ch.epfl.bluebrain.nexus.delta.sourcing.tombstone

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.EventTombstoneStore.Value
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all._
import munit.AnyFixture

class EventTombstoneStoreSuite extends NexusSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobieTruncateAfterTest)

  private lazy val xas            = doobieTruncateAfterTest()
  private lazy val tombstoneStore = new EventTombstoneStore(xas)

  test("Save an event tombstone for the given identifier") {
    val tpe     = EntityType("test")
    val project = ProjectRef.unsafe("org", "project")
    val id      = nxv + "id"
    val subject = User("user", Label.unsafe("realm"))

    tombstoneStore.save(tpe, project, id, subject).transact(xas.write) >>
      tombstoneStore.count.assertEquals(1L) >>
      tombstoneStore
        .unsafeGet(project, id)
        .map(_.map(_.value))
        .assertEquals(Some(Value(subject)))
  }
}
