package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.clock.MutableClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
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

  private val entityType     = EntityType("test")
  private val scopeInitError = ScopeInitializationFailed("boom")

//  override def beforeAll(): Unit = {
//    super.beforeAll()
// //     clear the scoped_initialization_errors table
//    sql"""DELETE FROM scope_initialization_errors""".update.run.void.transact(xas.write).accepted
//  }

  test("Clear table") {
    sql"""DELETE FROM scope_initialization_errors""".update.run.void.transact(xas.write)
  }

  test("Inserting an error should succeed") {
    val project     = genRandomProjectRef()
    // format: off
    val expectedRow = 
      List(ScopeInitErrorRow(1, entityType.value, project.organization.value, project.project.value, scopeInitError.reason, Instant.EPOCH))
    // format: on

    saveSimpleError(project) >>
      errorStore.fetch(project).assertEquals(expectedRow)
  }

  test("The count should be zero for a project without errors") {
    val project = genRandomProjectRef()
    errorStore.count(project).assertEquals(0)
  }

  test("The count should be correct for a project that has errors") {
    val project = genRandomProjectRef()
    saveSimpleError(project) >>
      saveSimpleError(project) >>
      errorStore.count(project).assertEquals(2)
  }

  private def genRandomProjectRef() =
    ProjectRef(Label.unsafe(genString()), Label.unsafe(genString()))

  private def saveSimpleError(project: ProjectRef) =
    errorStore.save(entityType, project, scopeInitError)

}
