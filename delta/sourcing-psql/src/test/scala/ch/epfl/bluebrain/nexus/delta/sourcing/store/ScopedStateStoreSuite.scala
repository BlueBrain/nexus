package ch.epfl.bluebrain.nexus.delta.sourcing.store

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestClose, PullRequestOpen}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.{DoobieAssertions, DoobieFixture, MonixBioSuite, PullRequest}
import doobie.implicits._

import java.time.Instant

class ScopedStateStoreSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ScopedStateStore[Label, PullRequestState](
    PullRequest.entityType,
    PullRequestState.serializer,
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")

  private val id1 = Label.unsafe("1")
  private val id2 = Label.unsafe("2")

  private val customTag = UserTag.unsafe("v0.1")

  private val state1        = PullRequestOpen(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = PullRequestOpen(id2, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val updatedState1 = PullRequestClose(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val state3 = PullRequestOpen(id1, project2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private def assertCount(expected: Int) =
    assertIO(sql"select count(*) from scoped_states".query[Int].unique.transact(xas.read), expected)

  test("Save state 1, state 2 and state 3 successfully") {
    for {
      _ <- List(state1, state2, state3).traverse(store.save).transact(xas.write)
      _ <- assertCount(3)
    } yield ()
  }

  test("get state 1") {
    assertIOSome(
      store.get(project1, id1),
      state1
    )
  }

  test("Save state 1 and state 3 with user tag successfully") {
    for {
      _ <- List(state1, state3).traverse(store.save(_, customTag)).transact(xas.write)
      _ <- assertCount(5)
    } yield ()
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.save(updatedState1).transact(xas.write)
      _ <- assertCount(5)
      _ <- assertIOSome(store.get(project1, id1), updatedState1)
    } yield ()
  }

  test("Delete tagged state 3 successfully") {
    for {
      _ <- store.delete(project2, id1, customTag).transact(xas.write)
      _ <- assertCount(4)
      _ <- assertIONone(store.get(project2, id1, customTag))
    } yield ()
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete(project1, id2, Latest).transact(xas.write)
      _ <- assertCount(3)
      _ <- assertIONone(store.get(project1, id2))
    } yield ()
  }

}
