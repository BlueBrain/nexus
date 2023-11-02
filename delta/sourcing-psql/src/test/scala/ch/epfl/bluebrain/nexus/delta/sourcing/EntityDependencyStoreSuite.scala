package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.{DependsOn, ReferencedBy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.bio.BioSuite
import doobie.implicits._
import io.circe.Decoder
import munit.AnyFixture

import java.time.Instant

class EntityDependencyStoreSuite extends BioSuite with CatsRunContext with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Stop)

  implicit val pullRequestDecoder: Decoder[PullRequestState] = PullRequestState.serializer.codec

  private lazy val stateStore = ScopedStateStore(
    PullRequest.entityType,
    PullRequestState.serializer,
    queryConfig,
    xas
  )

  private val id1          = nxv + "id1"
  private val id2          = nxv + "id2"
  private val id3          = nxv + "id3"
  private val id4          = nxv + "id4"
  private val id5          = nxv + "id5"
  private val proj         = ProjectRef.unsafe("org", "proj")
  private val projEntities = List(id1, id2, id3)

  private val proj2 = ProjectRef.unsafe("org", "proj2")

  private val bob = User("Bob", Label.unsafe("realm"))

  private val state1       = PullRequestActive(id1, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state2Tagged = PullRequestActive(id2, proj, 1, Instant.EPOCH, bob, Instant.EPOCH, bob)
  private val state2       = PullRequestActive(id2, proj, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state3Tagged = PullRequestActive(id3, proj, 1, Instant.EPOCH, bob, Instant.EPOCH, bob)
  private val state3       = PullRequestActive(id3, proj, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state5       = PullRequestActive(id5, proj2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  private val dependencyId1 = DependsOn(proj, id1)
  private val dependencyId2 = DependsOn(proj, id2)
  private val dependencyId3 = DependsOn(proj, id3)
  private val dependencyId4 = DependsOn(proj2, id4)
  private val dependencyId5 = DependsOn(proj2, id5)

  private val noTaggedStatesClue = "Tagged states should not be returned when fetching dependencies values."

  test("Save the different states") {
    val saveLatestStates = List(state1, state2, state3, state5).traverse(stateStore.unsafeSave(_))
    // Tagged states should not be returned when fetching the dependency values
    val saveTaggedStates = List(state2Tagged, state3Tagged).traverse(stateStore.unsafeSave(_, UserTag.unsafe("my-tag")))
    (saveLatestStates >> saveTaggedStates).transact(xas.write)
  }

  test("Insert the dependencies") {
    (EntityDependencyStore.save(
      proj,
      id1,
      Set(dependencyId2, dependencyId3)
    ) >>
      EntityDependencyStore.save(
        proj,
        id2,
        Set(dependencyId4)
      ) >>
      EntityDependencyStore.save(
        proj2,
        id4,
        Set(dependencyId5)
      )).transact(xas.write)
  }

  test("Fetch direct dependencies for id1") {
    EntityDependencyStore
      .directDependencies(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3)
      )
  }

  test("Fetch direct dependencies for id2") {
    EntityDependencyStore.directDependencies(proj, id2, xas).assert(Set(dependencyId4))
  }

  test("Fetch direct dependencies for id3") {
    EntityDependencyStore.directDependencies(proj, id3, xas).assert(Set.empty)
  }

  test(s"Fetch direct external references for $proj") {
    EntityDependencyStore
      .directExternalReferences(proj, xas)
      .assert(Set.empty)
  }

  test(s"Fetch direct external references for $proj2") {
    EntityDependencyStore
      .directExternalReferences(proj2, xas)
      .assert(
        Set(ReferencedBy(proj, id2))
      )
  }

  test("Fetch latest state values for direct dependencies of id1") {
    EntityDependencyStore.decodeDirectDependencies(proj, id1, xas).assert(List(state2, state3), noTaggedStatesClue)
  }

  test("Fetch all dependencies for id1") {
    EntityDependencyStore
      .recursiveDependencies(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3, dependencyId4, dependencyId5)
      )
  }

  test("Fetch latest state values for all dependencies for id1") {
    EntityDependencyStore
      .decodeRecursiveDependencies(proj, id1, xas)
      .assert(List(state2, state3, state5), noTaggedStatesClue)
  }

  test("Introducing a dependency cycle") {
    EntityDependencyStore.save(
      proj,
      id3,
      Set(dependencyId1)
    )
  }

  test("And another one") {
    EntityDependencyStore.save(
      proj,
      id5,
      Set(dependencyId1)
    )
  }

  test("Fetch again all dependencies for id1 to check that cycles are prevented") {
    EntityDependencyStore
      .recursiveDependencies(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3, dependencyId4, dependencyId5)
      )
  }

  test("Fetch latest state values for all dependencies for id1 to check that cycles are prevented") {
    EntityDependencyStore
      .decodeRecursiveDependencies(proj, id1, xas)
      .assert(List(state2, state3, state5), noTaggedStatesClue)
  }

  test(s"Delete all dependencies for $proj") {
    for {
      _ <- EntityDependencyStore.deleteAll(proj).transact(xas.write)
      _ <- projEntities.traverse { id =>
             EntityDependencyStore
               .directDependencies(proj, id, xas)
               .assert(
                 Set.empty,
                 s"Dependencies for '$id' in '$proj' should have been deleted."
               )
           }
    } yield ()
  }

}
