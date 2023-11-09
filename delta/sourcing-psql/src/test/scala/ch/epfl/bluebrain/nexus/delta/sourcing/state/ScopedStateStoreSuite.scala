package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.ThrowableValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.{entityType, PullRequestState}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityCheck, PullRequest, Scope}
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ScopedStateStoreSuite extends CatsEffectSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private lazy val store = ScopedStateStore[Iri, PullRequestState](
    PullRequest.entityType,
    PullRequestState.serializer,
    QueryConfig(1, RefreshStrategy.Delay(500.millis)),
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj2")

  private val id1 = nxv + "1"
  private val id2 = nxv + "2"
  private val id4 = nxv + "4"

  private val customTag = UserTag.unsafe("v0.1")

  private val state1        = PullRequestActive(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = PullRequestActive(id2, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val updatedState1 = PullRequestClosed(id1, project1, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val state3 = PullRequestActive(id1, project2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state4 = PullRequestActive(id4, project3, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val envelope1        = Envelope(PullRequest.entityType, id1, 1, state1, Instant.EPOCH, Offset.at(1L))
  private val envelope2        = Envelope(PullRequest.entityType, id2, 1, state2, Instant.EPOCH, Offset.at(2L))
  private val envelope3        = Envelope(PullRequest.entityType, id1, 1, state3, Instant.EPOCH, Offset.at(3L))
  private val envelope4        = Envelope(PullRequest.entityType, id4, 1, state4, Instant.EPOCH, Offset.at(4L))
  private val envelope1Tagged  = Envelope(PullRequest.entityType, id1, 1, state1, Instant.EPOCH, Offset.at(5L))
  private val envelope3Tagged  = Envelope(PullRequest.entityType, id1, 1, state3, Instant.EPOCH, Offset.at(6L))
  private val envelopeUpdated1 = Envelope(PullRequest.entityType, id1, 2, updatedState1, Instant.EPOCH, Offset.at(7L))

  private def assertCount(expected: Int) =
    sql"select count(*) from scoped_states".query[Int].unique.transact(xas.read).assertEquals(expected)

  test("Save state 1, state 2 and state 3 successfully") {
    for {
      _ <- List(state1, state2, state3, state4).traverse(store.unsafeSave).transact(xas.write)
      _ <- assertCount(4)
    } yield ()
  }

  test("get state 1") {
    store.get(project1, id1).assertEquals(state1)
  }

  test("Save state 1 and state 3 with user tag successfully") {
    for {
      _ <- List(state1, state3).traverse(store.unsafeSave(_, customTag)).transact(xas.write)
      _ <- assertCount(6)
    } yield ()
  }

  test("Fetch all current latest states from the beginning") {
    store.currentStates(Scope.Root).assert(envelope1, envelope2, envelope3, envelope4)
  }

  test("Fetch all latest states from the beginning") {
    store.states(Scope.Root).assert(envelope1, envelope2, envelope3, envelope4)
  }

  test(s"Fetch current states for ${project1.organization} from the beginning") {
    store.currentStates(Scope.Org(project1.organization)).assert(envelope1, envelope2, envelope3)
  }

  test(s"Fetch states for  ${project1.organization} from the beginning") {
    store.states(Scope.Org(project1.organization)).assert(envelope1, envelope2, envelope3)
  }

  test(s"Fetch current states for $project1 from offset 2") {
    store.currentStates(Scope.Project(project1), Offset.at(1L)).assert(envelope2)
  }

  test(s"Fetch states for $project1 from offset 2") {
    store.states(Scope.Project(project1), Offset.at(1L)).assert(envelope2)
  }

  test(s"Fetch all current states from the beginning for tag `$customTag`") {
    store.currentStates(Scope.Root, customTag).assert(envelope1Tagged, envelope3Tagged)
  }

  test(s"Fetch all states from the beginning for tag `$customTag`") {
    store.states(Scope.Root, customTag).assert(envelope1Tagged, envelope3Tagged)
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.unsafeSave(updatedState1).transact(xas.write)
      _ <- assertCount(6)
      _ <- store.get(project1, id1).assertEquals(updatedState1)
    } yield ()
  }

  test("Fetch all current latest states from the beginning") {
    store.currentStates(Scope.Root).assert(envelope2, envelope3, envelope4, envelopeUpdated1)
  }

  test("Delete tagged state 3 successfully") {
    for {
      _ <- store.delete(project2, id1, customTag).transact(xas.write)
      _ <- assertCount(5)
      _ <- store.get(project2, id1, customTag).intercept(TagNotFound)
    } yield ()
  }

  test(s"Fetch all states from the beginning for tag `$customTag` after deletion of `state3`") {
    store.states(Scope.Root, customTag).assert(envelope1Tagged)
  }

  test("Check that the given ids does exist") {
    EntityCheck
      .raiseMissingOrDeprecated[Iri, Nothing](
        entityType,
        Set(project1 -> id2, project2 -> id1),
        _ => fail("Should not be called"),
        xas
      )
      .assertEquals(())
  }

  case class EntityCheckError(value: Set[(ProjectRef, Iri)]) extends ThrowableValue

  test("Check that the non existing ids are returned") {
    val unknowns: Set[(ProjectRef, Iri)] =
      Set(project1 -> id1, project1 -> (nxv + "xxx"), ProjectRef.unsafe("xxx", "xxx") -> id4)
    EntityCheck
      .raiseMissingOrDeprecated[Iri, EntityCheckError](
        entityType,
        Set(project1 -> id1, project1 -> id2) ++ unknowns,
        value => EntityCheckError(value),
        xas
      )
      .assertError[EntityCheckError](_.value == unknowns)
  }

  test("Get the entity type for id1 in project 1") {
    EntityCheck
      .findType(
        id1,
        project1,
        xas
      )
      .assertSome(PullRequest.entityType)
  }

  test("Get no entity type for an unknown id") {
    EntityCheck
      .findType(
        Label.unsafe("xxx"),
        project1,
        xas
      )
      .assertNone
  }

  test("Get no entity type for an unknown project") {
    EntityCheck
      .findType(
        id1,
        ProjectRef.unsafe("org", "xxx"),
        xas
      )
      .assertNone
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete(project1, id2, Latest).transact(xas.write)
      _ <- assertCount(4)
      _ <- store.get(project1, id2).intercept(UnknownState)
    } yield ()
  }

}
