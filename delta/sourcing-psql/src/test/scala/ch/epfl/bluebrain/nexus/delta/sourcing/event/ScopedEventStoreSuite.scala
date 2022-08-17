package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{Predicate, PullRequest}
import ch.epfl.bluebrain.nexus.testkit.{DoobieAssertions, DoobieFixture, MonixBioSuite}
import doobie.implicits._

import java.time.Instant
import scala.concurrent.duration._

class ScopedEventStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ScopedEventStore[Label, PullRequestEvent](
    PullRequest.entityType,
    PullRequestEvent.serializer,
    QueryConfig(10, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")

  private val id1 = Label.unsafe("1")
  private val id2 = Label.unsafe("2")
  private val id3 = Label.unsafe("3")

  private val event1 = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2 = PullRequestUpdated(id1, project1, 2, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))
  private val event3 = PullRequestMerged(id1, project1, 3, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))

  private val event4 = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)

  private val event5 = PullRequestCreated(id1, project2, Instant.EPOCH, Anonymous)

  private val event6 = PullRequestCreated(id3, project3, Instant.EPOCH, Anonymous)

  private val envelope1 = Envelope(PullRequest.entityType, id1, 1, event1, Instant.EPOCH, Offset.at(1L))
  private val envelope2 = Envelope(PullRequest.entityType, id1, 2, event2, Instant.EPOCH, Offset.at(2L))
  private val envelope3 = Envelope(PullRequest.entityType, id1, 3, event3, Instant.EPOCH, Offset.at(3L))
  private val envelope4 = Envelope(PullRequest.entityType, id2, 1, event4, Instant.EPOCH, Offset.at(4L))
  private val envelope5 = Envelope(PullRequest.entityType, id1, 1, event5, Instant.EPOCH, Offset.at(5L))
  private val envelope6 = Envelope(PullRequest.entityType, id3, 1, event6, Instant.EPOCH, Offset.at(6L))

  private def assertCount = sql"select count(*) from scoped_events".query[Int].unique.transact(xas.read).assert(6)

  test("Save events") {
    for {
      _ <- List(event1, event2, event3, event4, event5, event6).traverse(store.save).transact(xas.write)
      _ <- assertCount
    } yield ()
  }

  test("Fail when the PK already exists") {
    for {
      _ <- store
             .save(PullRequestMerged(id1, project1, 2, Instant.EPOCH, Anonymous))
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
      .currentEvents(Predicate.Root, Offset.Start)
      .assert(envelope1, envelope2, envelope3, envelope4, envelope5, envelope6)
  }

  test("Fetch current events for `org` from offset 2") {
    store.currentEvents(Predicate.Org(Label.unsafe("org")), Offset.at(2L)).assert(envelope3, envelope4, envelope5)
  }

  test("Fetch current events for `proj1` from the beginning") {
    store.currentEvents(Predicate.Project(project1), Offset.Start).assert(envelope1, envelope2, envelope3, envelope4)
  }

  test("Fetch all events from the beginning") {
    store.events(Predicate.Root, Offset.Start).assert(envelope1, envelope2, envelope3, envelope4, envelope5, envelope6)
  }

  test(s"Fetch current events for `${project1.organization}` from offset 2") {
    store.events(Predicate.Org(project1.organization), Offset.at(2L)).assert(envelope3, envelope4, envelope5)
  }

  test(s"Fetch current events for `$project1` from the beginning") {
    store.events(Predicate.Project(project1), Offset.Start).assert(envelope1, envelope2, envelope3, envelope4)
  }

}
