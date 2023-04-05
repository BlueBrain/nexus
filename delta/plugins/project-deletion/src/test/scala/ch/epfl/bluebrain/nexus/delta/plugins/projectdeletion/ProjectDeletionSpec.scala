package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model.ProjectDeletionConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.testkit.IOValues
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.genString
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Duration, Instant}
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.matching.Regex

class ProjectDeletionSpec extends AnyWordSpec with Matchers with IOValues {
  case class ProjectFixture(
      deprecated: Boolean,
      updatedAt: Instant,
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

  private def projectWith(
      deprecated: Boolean = false,
      updatedAt: Instant = Instant.now(),
      org: String = genId(),
      label: String = genId(),
      id: Iri = nxv + genId(),
      markedForDeletion: Boolean = false
  ) = {
    ProjectFixture(deprecated, updatedAt, org, label, id, markedForDeletion)
  }

  private def genId(length: Int = 15): String =
    genString(length = length, Vector.range('a', 'z') ++ Vector.range('0', '9'))
  private def config(
      deleteDeprecatedProjects: Boolean,
      idleInterval: FiniteDuration,
      includedProjects: List[Regex],
      excludedProjects: List[Regex]
  ): ProjectDeletionConfig = {
    ProjectDeletionConfig(
      idleInterval,
      idleCheckPeriod = 1.day,
      deleteDeprecatedProjects,
      includedProjects,
      excludedProjects
    )
  }

  private def addTo(deletedProjects: mutable.Set[ProjectResource]): ProjectResource => UIO[Unit] = { pr =>
    {
      IO.evalTotal {
        deletedProjects.add(pr)
        ()
      }
    }
  }

  private def runWith(
      projects: List[ProjectFixture],
      deleteDeprecatedProjects: Boolean = false,
      idleInterval: FiniteDuration = 1.second,
      includedProjects: List[Regex] = List(".*".r),
      excludedProjects: List[Regex] = Nil
  ) = {
    val deletedProjects = mutable.Set.empty[ProjectResource]

    ProjectDeletionPlugin
      .projectDeletionPass(
        allProjects = UIO.pure(projects.map(_.resource)),
        deleteProject = addTo(deletedProjects),
        config(deleteDeprecatedProjects, idleInterval, includedProjects, excludedProjects),
        lastEventTime = (pr, now) => UIO.pure(projects.find(_.id == pr.id).map(_.updatedAt).getOrElse(now))
      )
      .accepted

    deletedProjects
  }

  "Project deletion" should {
    "delete a project when deprecated" when {
      "if enabled" in {
        val deprecatedProject    = projectWith(deprecated = true)
        val nonDeprecatedProject = projectWith(deprecated = false)

        val deletedProjects =
          runWith(projects = List(deprecatedProject, nonDeprecatedProject), deleteDeprecatedProjects = true)

        deletedProjects should contain(deprecatedProject.resource)
        deletedProjects should not contain nonDeprecatedProject.resource
      }

      "not if disabled" in {
        val deprecatedProject = projectWith(deprecated = true)

        val deletedProjects = runWith(projects = List(deprecatedProject), deleteDeprecatedProjects = false)

        deletedProjects should not contain deprecatedProject.resource
      }
    }

    val TwoDaysAgo    = Instant.now().minus(Duration.ofDays(2))
    val ThreeHoursAgo = Instant.now().minus(Duration.ofHours(3))

    "delete a project if it has been inactive for too long" in {
      val inactiveProject = projectWith(updatedAt = TwoDaysAgo)
      val activeProject   = projectWith(updatedAt = ThreeHoursAgo)

      val deletedProjects = runWith(projects = List(inactiveProject, activeProject), idleInterval = 24.hours)

      deletedProjects should contain(inactiveProject.resource)
      deletedProjects should not contain activeProject.resource
    }

    "only delete projects which match the specified inclusion regex" in {
      val matchingProject       = projectWith(updatedAt = TwoDaysAgo, org = "cinelli", label = "vigorelli")
      val secondMatchingProject = projectWith(updatedAt = TwoDaysAgo, org = "skream", label = "magnum")
      val nonMatchingProject    = projectWith(updatedAt = TwoDaysAgo, org = "genesis", label = "flyer")

      val deletedProjects = runWith(
        projects = List(matchingProject, secondMatchingProject, nonMatchingProject),
        idleInterval = 24.hours,
        includedProjects = List("cinelli.+".r, ".*magnum".r)
      )

      deletedProjects should contain(matchingProject.resource)
      deletedProjects should contain(secondMatchingProject.resource)
      deletedProjects should not contain nonMatchingProject.resource
    }

    "not delete projects which match the specified exclusion regex" in {
      val matchingProject       = projectWith(updatedAt = TwoDaysAgo, org = "cinelli", label = "vigorelli")
      val secondMatchingProject = projectWith(updatedAt = TwoDaysAgo, org = "skream", label = "magnum")
      val nonMatchingProject    = projectWith(updatedAt = TwoDaysAgo, org = "genesis", label = "flyer")

      val deletedProjects = runWith(
        projects = List(matchingProject, secondMatchingProject, nonMatchingProject),
        idleInterval = 24.hours,
        excludedProjects = List("cinelli.+".r, ".*magnum".r)
      )

      deletedProjects should contain(nonMatchingProject.resource)
      deletedProjects should not contain secondMatchingProject.resource
      deletedProjects should not contain matchingProject.resource
    }

    "not run against already deleted projects" in {
      val alreadyDeletedProject = projectWith(updatedAt = TwoDaysAgo, markedForDeletion = true)

      val deletedProjects = runWith(projects = List(alreadyDeletedProject), idleInterval = 24.hours)

      deletedProjects should not contain alreadyDeletedProject.resource
    }
  }
}
