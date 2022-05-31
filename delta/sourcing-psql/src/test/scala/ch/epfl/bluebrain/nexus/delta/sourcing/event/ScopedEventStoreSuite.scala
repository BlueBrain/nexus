package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.SourcingConfig.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{DoobieAssertions, DoobieFixture, MonixBioSuite, PullRequest}
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

  private val id1 = Label.unsafe("1")
  private val id2 = Label.unsafe("2")

  private val event1 = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2 = PullRequestUpdated(id1, project1, 2, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))
  private val event3 = PullRequestMerged(id1, project1, 3, Instant.EPOCH, User("Alice", Label.unsafe("Wonderland")))

  private val event4 = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)

  private val event5 = PullRequestCreated(id1, project2, Instant.EPOCH, Anonymous)

  private def assertCount = sql"select count(*) from scoped_events".query[Int].unique.transact(xas.read).assert(5)

  test("Save events") {
    for {
      _ <- List(event1, event2, event3, event4, event5).traverse(store.save).transact(xas.write)
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

}
