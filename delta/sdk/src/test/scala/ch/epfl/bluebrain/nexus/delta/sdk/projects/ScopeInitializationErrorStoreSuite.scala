package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.clock.MutableClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import doobie.postgres.implicits._
import munit.AnyFixture

import java.time.Instant

class ScopeInitializationErrorStoreSuite
    extends NexusSuite
    with MutableClock.Fixture
    with Doobie.Fixture
    with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie, mutableClockFixture)
  private lazy val xas                           = doobie()
  private lazy val mutableClock: MutableClock    = mutableClockFixture()

  private lazy val errorStore = ScopeInitializationErrorStore(xas, mutableClock)

  private val project        = ProjectRef.unsafe("org", "proj")
  private val entityType     = EntityType("test")
  private val scopeInitError = ScopeInitializationFailed("boom")

  private val scopeInitErrorRow = ScopeInitErrorRow(
    1,
    entityType.value,
    project.organization.value,
    project.project.value,
    scopeInitError.reason,
    Instant.EPOCH
  )

  test("Inserting an error should succeed") {
    assertStoreIsEmpty >>
      errorStore.save(entityType, project, scopeInitError) >>
      errorStore.selectOne.assertEquals(scopeInitErrorRow)
  }

  case class ScopeInitErrorRow(
      ordering: Int,
      entityType: String,
      org: String,
      project: String,
      message: String,
      instant: Instant
  )

  implicit class ScopeInitErrorStoreOps(store: ScopeInitializationErrorStore) {
    def count: IO[Int] =
      sql"""SELECT COUNT(*) FROM scope_initialization_errors""".query[Int].unique.transact(xas.read)

    def selectOne: IO[ScopeInitErrorRow] =
      sql"""SELECT ordering, type, org, project, message, instant FROM scope_initialization_errors"""
        .query[ScopeInitErrorRow]
        .unique
        .transact(xas.read)
  }

  private def assertStoreIsEmpty = errorStore.count.assertEquals(0)

}
