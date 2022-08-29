package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityDependency, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import io.circe.Decoder
import munit.AnyFixture

import java.time.Instant

class EntityDependencyStoreSuite extends BioSuite with Doobie.Fixture {

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

  private val id1   = Label.unsafe("id1")
  private val id2   = Label.unsafe("id2")
  private val id3   = Label.unsafe("id3")
  private val id4   = Label.unsafe("id4")
  private val id5   = Label.unsafe("id5")
  private val proj  = ProjectRef.unsafe("org", "proj")
  private val proj2 = ProjectRef.unsafe("org", "proj2")

  private val state1 = PullRequestActive(id1, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state2 = PullRequestActive(id2, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state3 = PullRequestActive(id3, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state5 = PullRequestActive(id5, proj2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  private val dependencyId1 = EntityDependency(proj, id1.toString)
  private val dependencyId2 = EntityDependency(proj, id2.toString)
  private val dependencyId3 = EntityDependency(proj, id3.toString)
  private val dependencyId4 = EntityDependency(proj2, id4.toString)
  private val dependencyId5 = EntityDependency(proj2, id5.toString)

  test("Save the different states") {
    List(state1, state2, state3, state5).traverse(stateStore.save(_)).transact(xas.write)
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
      .list(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3)
      )
  }

  test("Fetch direct dependencies for id2") {
    EntityDependencyStore.list(proj, id2, xas).assert(Set(dependencyId4))
  }

  test("Fetch direct dependencies for id3") {
    EntityDependencyStore.list(proj, id3, xas).assert(Set.empty)
  }

  test("Fetch values for direct dependencies of id1") {
    EntityDependencyStore.decodeList(proj, id1, xas).assert(List(state2, state3))
  }

  test("Fetch all dependencies for id1") {
    EntityDependencyStore
      .recursiveList(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3, dependencyId4, dependencyId5)
      )
  }

  test("Fetch values for all dependencies for id1") {
    EntityDependencyStore
      .decodeRecursiveList(proj, id1, xas)
      .assert(
        List(state2, state3, state5)
      )
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
      .recursiveList(proj, id1, xas)
      .assert(
        Set(dependencyId2, dependencyId3, dependencyId4, dependencyId5)
      )
  }

  test("Fetch values for all dependencies for id1 to check that cycles are prevented") {
    EntityDependencyStore
      .decodeRecursiveList(proj, id1, xas)
      .assert(
        List(state2, state3, state5)
      )
  }

}
