package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.ProjectDeletionLogicSpec.{assertDeleted, assertNotDeleted, configWhere, projectWhere, runWith, ThreeHoursAgo, TwoDaysAgo}
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.Result.{Deleted, NotDeleted}
import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.genString
import ch.epfl.bluebrain.nexus.testkit.bio.{BioAssertions, BioSuite}
import monix.bio.{IO, UIO}
import munit.{Assertions, Location}

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.matching.Regex

class ProjectDeletionLogicSpec extends BioSuite {

  test("delete a deprecated project") {
    assertDeleted(
      runWith(
        configWhere(deleteDeprecatedProjects = true),
        projectWhere(deprecated = true)
      )
    )
  }

  test("not delete a non-deprecated project") {
    assertNotDeleted(
      runWith(
        configWhere(deleteDeprecatedProjects = true),
        projectWhere(deprecated = false)
      )
    )
  }

  test("not delete a deprecated project if the feature is disabled") {
    assertNotDeleted(
      runWith(
        configWhere(deleteDeprecatedProjects = false),
        projectWhere(deprecated = true)
      )
    )
  }

  test("delete a project which has been inactive too long") {
    assertDeleted(
      runWith(
        configWhere(idleInterval = 24.hours),
        projectWhere(updatedAt = TwoDaysAgo, lastEventTime = TwoDaysAgo)
      )
    )
  }

  test("not delete a project which has been updated recently") {
    assertNotDeleted(
      runWith(
        configWhere(idleInterval = 24.hours),
        projectWhere(updatedAt = ThreeHoursAgo, lastEventTime = TwoDaysAgo)
      )
    )
  }

  test("not delete a project which has recent events") {
    assertNotDeleted(
      runWith(
        configWhere(idleInterval = 24.hours),
        projectWhere(updatedAt = TwoDaysAgo, lastEventTime = ThreeHoursAgo)
      )
    )
  }

  test("not delete a project if the org/label does not match the inclusion regex") {
    assertNotDeleted(
      runWith(
        configWhere(idleInterval = 24.hours, includedProjects = List("look.+".r, ".*magnum".r)),
        projectWhere(updatedAt = ThreeHoursAgo, org = "cinelli", label = "vigorelli")
      )
    )
  }

  test("not delete a project if the org/label matches the exclusion regex") {
    assertNotDeleted(
      runWith(
        configWhere(idleInterval = 24.hours, excludedProjects = List("look.+".r, ".*magnum".r)),
        projectWhere(updatedAt = ThreeHoursAgo, org = "skream", label = "magnum")
      )
    )
  }

  test("not run against already deleted projects") {
    assertNotDeleted(
      runWith(
        configWhere(idleInterval = 24.hours),
        projectWhere(updatedAt = TwoDaysAgo, markedForDeletion = true)
      )
    )
  }
}

object ProjectDeletionLogicSpec extends Assertions with BioAssertions {
  case class ProjectFixture(
                             deprecated: Boolean,
                             updatedAt: Instant,
                             lastEventTime: Instant,
                             org: String,
                             label: String,
                             id: Iri,
                             markedForDeletion: Boolean
  ) {
    val resource = {
      val project = ProjectGen.project(
        orgLabel = org,
        label = label,
        markedForDeletion = markedForDeletion
      )

      ResourceF[Project](
        id = id,
        uris = ResourceUris.project(project.ref),
        rev = 0,
        types = Set.empty,
        deprecated = deprecated,
        createdAt = Instant.EPOCH,
        createdBy = Anonymous,
        updatedAt = updatedAt,
        updatedBy = Anonymous,
        schema = ResourceRef(schemas.resources),
        value = project
      )
    }
  }

  def projectWhere(
                    deprecated: Boolean = false,
                    updatedAt: Instant = Instant.now(),
                    lastEventTime: Instant = Instant.now(),
                    org: String = genId(),
                    label: String = genId(),
                    id: Iri = nxv + genId(),
                    markedForDeletion: Boolean = false
  ) = {
    ProjectFixture(deprecated, updatedAt, lastEventTime, org, label, id, markedForDeletion)
  }

  def genId(length: Int = 15): String =
    genString(length = length, Vector.range('a', 'z') ++ Vector.range('0', '9'))

  def configWhere(
      deleteDeprecatedProjects: Boolean = false,
      idleInterval: FiniteDuration = 1.second,
      includedProjects: List[Regex] = List(".*".r),
      excludedProjects: List[Regex] = Nil
  ): ProjectDeletionConfig = {
    ProjectDeletionConfig(
      idleInterval,
      idleCheckPeriod = 1.day,
      deleteDeprecatedProjects,
      includedProjects,
      excludedProjects
    )
  }

  def addTo(deletedProjects: mutable.Set[ProjectResource]): ProjectResource => UIO[Unit] = { pr =>
    IO.evalTotal {
      deletedProjects.add(pr)
      ()
    }
  }

  def assertDeleted(result: UIO[Result])(implicit loc: Location): UIO[Unit] = {
    assertUIO[Result](result, _ == Result.Deleted, "project was not deleted")
  }

  def assertNotDeleted(result: UIO[Result])(implicit loc: Location): UIO[Unit] = {
    assertUIO[Result](result, _ == Result.NotDeleted, "project was deleted")
  }

  val TwoDaysAgo    = Instant.now().minus(Duration.ofDays(2))
  val ThreeHoursAgo = Instant.now().minus(Duration.ofHours(3))

  def runWith(
      config: ProjectDeletionConfig,
      project: ProjectFixture
  ): UIO[Result] = {
    val deletedProjects = mutable.Set.empty[ProjectResource]

    val deleter = new ProjectDeleter(
      deleteProject = addTo(deletedProjects),
      config,
      lastEventTime = (_, _) => UIO.pure(project.lastEventTime))
    deleter
      .processProject(
        project.resource,
        Instant.now()
      )
      .map { _ =>
        if (deletedProjects.isEmpty) {
          NotDeleted
        } else {
          Deleted
        }
      }
  }

}

sealed trait Result

object Result {
  case object Deleted extends Result

  case object NotDeleted extends Result
}
