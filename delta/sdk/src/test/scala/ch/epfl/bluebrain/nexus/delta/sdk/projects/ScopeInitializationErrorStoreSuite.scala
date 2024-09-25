package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore.ScopeInitErrorRow
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.testkit.clock.MutableClock
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.syntax.all._
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

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    sql"""DELETE FROM scope_initialization_errors""".update.run.void.transact(xas.write).accepted
  }

  test("Inserting an error should succeed") {
    val project     = genRandomProjectRef()
    // format: off
    val expectedRow = 
      List(ScopeInitErrorRow(1, entityType, project.organization, project.project, scopeInitError.reason, Instant.EPOCH))
    // format: on

    saveSimpleError(project) >>
      errorStore.fetch.assertEquals(expectedRow)
  }

  test("The count should be zero for a project when there are no errors") {
    assertIO(errorStore.fetch, List.empty)
  }

  test("The count of errors is correct when there are errors across several projects") {
    val (project1, project2) = (genRandomProjectRef(), genRandomProjectRef())
    saveSimpleError(project1) >>
      saveSimpleError(project2) >>
      assertIO(errorStore.fetch.map(_.size), 2)
  }

  test("Deleting should only delete the errors for the provided project") {
    val (project1, project2) = (genRandomProjectRef(), genRandomProjectRef())
    saveSimpleError(project1) >> saveSimpleError(project2) >>
      errorStore.delete(project1) >>
      numberOfErrorsIn(project1).assertEquals(0) >>
      numberOfErrorsIn(project2).assertEquals(1)
  }

  private def genRandomProjectRef() =
    ProjectRef(Label.unsafe(genString()), Label.unsafe(genString()))

  private def saveSimpleError(project: ProjectRef) =
    errorStore.save(entityType, project, scopeInitError)

  private def numberOfErrorsIn(project: ProjectRef) =
    errorStore.fetch.map(_.count(x => ProjectRef(x.org, x.project) == project))

}
