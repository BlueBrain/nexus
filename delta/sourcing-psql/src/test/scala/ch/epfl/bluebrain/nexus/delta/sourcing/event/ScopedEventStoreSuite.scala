package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.{PullRequest, Scope}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ScopedEventStoreSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ScopedEventStore[Iri, PullRequestEvent](
    PullRequest.entityType,
    PullRequestEvent.serializer,
    QueryConfig(10, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")

  private val id1 = nxv + "1"
  private val id2 = nxv + "2"
  private val id3 = nxv + "3"

  private val event1 = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2 = PullRequestUpdated(id1, project1, 2, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))
  private val event3 = PullRequestMerged(id1, project1, 3, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))

  private val event4 = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)

  private val event5 = PullRequestCreated(id1, project2, Instant.EPOCH, Anonymous)

  private val event6 = PullRequestCreated(id3, project3, Instant.EPOCH, Anonymous)

  private val envelope1 = Elem.SuccessElem(PullRequest.entityType, id1, None, Instant.EPOCH, Offset.at(1L), event1, 1)
  private val envelope2 = Elem.SuccessElem(PullRequest.entityType, id1, None, Instant.EPOCH, Offset.at(2L), event2, 2)
  private val envelope3 = Elem.SuccessElem(PullRequest.entityType, id1, None, Instant.EPOCH, Offset.at(3L), event3, 3)
  private val envelope4 = Elem.SuccessElem(PullRequest.entityType, id2, None, Instant.EPOCH, Offset.at(4L), event4, 1)
  private val envelope5 = Elem.SuccessElem(PullRequest.entityType, id1, None, Instant.EPOCH, Offset.at(5L), event5, 1)
  private val envelope6 = Elem.SuccessElem(PullRequest.entityType, id3, None, Instant.EPOCH, Offset.at(6L), event6, 1)

  private def assertCount = sql"select count(*) from scoped_events".query[Int].unique.transact(xas.read).assertEquals(6)

  test("Save events") {
    for {
      _ <- List(event1, event2, event3, event4, event5, event6).traverse(store.unsafeSave).transact(xas.write)
      _ <- assertCount
    } yield ()
  }

  test("Fail when the PK already exists") {
    for {
      _ <- store
             .unsafeSave(PullRequestMerged(id1, project1, 2, Instant.EPOCH, Anonymous))
             .transact(xas.write)
             .expectUniqueViolation
      _ <- assertCount
    } yield ()
  }

  test("Fetch all events for a given id") {
    store.history(project1, id1).assert(event1, event2, event3)
  }

  test("Fetch all events for a given id up to revision 2") {
    store.history(project1, id1, 2).assert(event1, event2)
  }

  test("Get an empty stream for an unknown (project, id)") {
    store.history(project2, id2, 2).assertEmpty
  }

  test("Fetch all current events from the beginning") {
    store
      .currentEvents(Scope.Root, Offset.Start)
      .assert(envelope1, envelope2, envelope3, envelope4, envelope5, envelope6)
  }

  test("Fetch current events for `org` from offset 2") {
    store.currentEvents(Scope.Org(Label.unsafe("org")), Offset.at(2L)).assert(envelope3, envelope4, envelope5)
  }

  test("Fetch current events for `proj1` from the beginning") {
    store.currentEvents(Scope.Project(project1), Offset.Start).assert(envelope1, envelope2, envelope3, envelope4)
  }

  test("Fetch all events from the beginning") {
    store.events(Scope.Root, Offset.Start).assert(envelope1, envelope2, envelope3, envelope4, envelope5, envelope6)
  }

  test(s"Fetch current events for `${project1.organization}` from offset 2") {
    store.events(Scope.Org(project1.organization), Offset.at(2L)).assert(envelope3, envelope4, envelope5)
  }

  test(s"Fetch current events for `$project1` from the beginning") {
    store.events(Scope.Project(project1), Offset.Start).assert(envelope1, envelope2, envelope3, envelope4)
  }

}
