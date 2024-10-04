package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.data.NonEmptySet
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.ThrowableValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore.StateNotFound.{TagNotFound, UnknownState}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityCheck, PullRequest, Scope}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all._
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ScopedStateStoreSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val entityType: EntityType = PullRequest.entityType
  private lazy val store             = ScopedStateStore[Iri, PullRequestState](
    entityType,
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

  private val epoch: Instant = Instant.EPOCH
  private val state1         = PullRequestActive(id1, project1, 1, epoch, Anonymous, epoch, alice)
  private val state2         = PullRequestActive(id2, project1, 1, epoch, Anonymous, epoch, alice)
  private val updatedState1  = PullRequestClosed(id1, project1, 2, epoch, Anonymous, epoch, alice)

  private val state3 = PullRequestActive(id1, project2, 1, epoch, Anonymous, epoch, alice)
  private val state4 = PullRequestActive(id4, project3, 1, epoch, Anonymous, epoch, alice)

  private val elem1        = Elem.SuccessElem(entityType, id1, project1, epoch, Offset.at(1L), state1, 1)
  private val elem2        = Elem.SuccessElem(entityType, id2, project1, epoch, Offset.at(2L), state2, 1)
  private val elem3        = Elem.SuccessElem(entityType, id1, project2, epoch, Offset.at(3L), state3, 1)
  private val elem4        = Elem.SuccessElem(entityType, id4, project3, epoch, Offset.at(4L), state4, 1)
  private val elem1Tagged  = Elem.SuccessElem(entityType, id1, project1, epoch, Offset.at(5L), state1, 1)
  private val elem3Tagged  = Elem.SuccessElem(entityType, id1, project2, epoch, Offset.at(6L), state3, 1)
  private val elem1Updated = Elem.SuccessElem(entityType, id1, project1, epoch, Offset.at(7L), updatedState1, 2)

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
    store.currentStates(Scope.Root, Offset.Start).assert(elem1, elem2, elem3, elem4)
  }

  test("Fetch all latest states from the beginning") {
    store.states(Scope.Root, Offset.Start).assert(elem1, elem2, elem3, elem4)
  }

  test(s"Fetch current states for ${project1.organization} from the beginning") {
    store.currentStates(Scope.Org(project1.organization), Offset.Start).assert(elem1, elem2, elem3)
  }

  test(s"Fetch states for  ${project1.organization} from the beginning") {
    store.states(Scope.Org(project1.organization), Offset.Start).assert(elem1, elem2, elem3)
  }

  test(s"Fetch current states for $project1 from offset 2") {
    store.currentStates(Scope.Project(project1), Offset.at(1L)).assert(elem2)
  }

  test(s"Fetch states for $project1 from offset 2") {
    store.states(Scope.Project(project1), Offset.at(1L)).assert(elem2)
  }

  test(s"Fetch all current states from the beginning for tag `$customTag`") {
    store.currentStates(Scope.Root, customTag, Offset.Start).assert(elem1Tagged, elem3Tagged)
  }

  test(s"Fetch all states from the beginning for tag `$customTag`") {
    store.states(Scope.Root, customTag, Offset.Start).assert(elem1Tagged, elem3Tagged)
  }

  test("Update state 1 successfully") {
    for {
      _ <- store.unsafeSave(updatedState1).transact(xas.write)
      _ <- assertCount(6)
      _ <- store.get(project1, id1).assertEquals(updatedState1)
    } yield ()
  }

  test("Fetch all current latest states from the beginning") {
    store.currentStates(Scope.Root, Offset.Start).assert(elem2, elem3, elem4, elem1Updated)
  }

  test("Delete tagged state 3 successfully") {
    for {
      _ <- store.delete(project2, id1, customTag).transact(xas.write)
      _ <- assertCount(5)
      _ <- store.get(project2, id1, customTag).interceptEquals(TagNotFound)
    } yield ()
  }

  test(s"Fetch all states from the beginning for tag `$customTag` after deletion of `state3`") {
    store.states(Scope.Root, customTag, Offset.Start).assert(elem1Tagged)
  }

  test("Check that the given ids does exist") {
    EntityCheck
      .raiseMissingOrDeprecated[Iri, Nothing](
        entityType,
        NonEmptySet.of(project1 -> id2, project2 -> id1),
        _ => fail("Should not be called"),
        xas
      )
      .assertEquals(())
  }

  case class EntityCheckError(value: Set[(ProjectRef, Iri)]) extends ThrowableValue

  test("Check that the non existing ids are returned") {
    val unknowns =
      NonEmptySet.of(project1 -> id1, project1 -> (nxv + "xxx"), ProjectRef.unsafe("xxx", "xxx") -> id4)
    EntityCheck
      .raiseMissingOrDeprecated[Iri, EntityCheckError](
        entityType,
        NonEmptySet.of(project1 -> id1, project1 -> id2) ++ unknowns,
        value => EntityCheckError(value),
        xas
      )
      .intercept[EntityCheckError]
      .assert(_.value == unknowns.toList.toSet)
  }

  test("Get the entity type for id1 in project 1") {
    EntityCheck
      .findType(
        id1,
        project1,
        xas
      )
      .assertEquals(Some(entityType))
  }

  test("Get no entity type for an unknown id") {
    EntityCheck
      .findType(
        Label.unsafe("xxx"),
        project1,
        xas
      )
      .assertEquals(None)
  }

  test("Get no entity type for an unknown project") {
    EntityCheck
      .findType(
        id1,
        ProjectRef.unsafe("org", "xxx"),
        xas
      )
      .assertEquals(None)
  }

  test("Delete state 2 successfully") {
    for {
      _ <- store.delete(project1, id2, Latest).transact(xas.write)
      _ <- assertCount(4)
      _ <- store.get(project1, id2).interceptEquals(UnknownState)
    } yield ()
  }

}
