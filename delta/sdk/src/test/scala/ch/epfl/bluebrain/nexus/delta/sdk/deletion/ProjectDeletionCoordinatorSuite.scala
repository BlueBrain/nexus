package ch.epfl.bluebrain.nexus.delta.sdk.deletion

import cats.effect.IO
import cats.effect.concurrent.Ref
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionCoordinator.{Active, Noop}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen.defaultApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsConfig.DeletionConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectNotFound, WrappedOrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, PrefixIri, ProjectFields}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{ProjectsConfig, ProjectsFixture}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDependencyStore
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import doobie.implicits._
import munit.AnyFixture

import java.time.Instant
import java.util.UUID

class ProjectDeletionCoordinatorSuite extends CatsEffectSuite with CatsRunContext with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("Bob", Label.unsafe("realm"))

  private val serviceAccount = ServiceAccount(subject)

  private val org     = Label.unsafe("org")
  private val orgUuid = UUID.randomUUID()

  private def fetchOrg: FetchOrganization = {
    case `org` => IO.pure(Organization(org, orgUuid, None))
    case other => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(other)))
  }

  private val deletionEnabled  = deletionConfig
  private val deletionDisabled = deletionConfig.copy(enabled = false)
  private val config           = ProjectsConfig(eventLogConfig, pagination, cacheConfig, deletionEnabled)

  private val projectFixture = ProjectsFixture.init(fetchOrg, defaultApiMappings, config)

  override def munitFixtures: Seq[AnyFixture[_]] = List(projectFixture)

  private lazy val (xas, projects) = projectFixture()

  private val active          = ProjectRef.unsafe("org", "active")
  private val deprecated      = ProjectRef.unsafe("org", "deprecated")
  private val markedAsDeleted = ProjectRef.unsafe("org", "deleted")
  private val entityToDelete  = nxv + "entity-to-delete"

  private val fields = ProjectFields(
    Some("Project description"),
    ApiMappings(
      "nxv" -> iri"https://localhost/nexus/vocabulary/",
      "rdf" -> iri"http://localhost/1999/02/22-rdf-syntax-ns#type"
    ),
    Some(PrefixIri.unsafe(iri"https://localhost/base/")),
    Some(PrefixIri.unsafe(iri"https://localhost/voc/"))
  )

  private val taskStage = ProjectDeletionReport.Stage.empty("test")

  private def initCoordinator(config: DeletionConfig) =
    Ref.of[IO, Set[ProjectRef]](Set.empty).map { deleted =>
      val deletionTask: ProjectDeletionTask = new ProjectDeletionTask {
        override def apply(project: ProjectRef)(implicit
            subject: Subject
        ): IO[ProjectDeletionReport.Stage] =
          deleted.update(_ + project).as(taskStage)
      }
      (deleted, ProjectDeletionCoordinator(projects, Set(deletionTask), config, serviceAccount, xas))
    }

  // Asserting partition number for both events and states
  private def assertPartitions(expected: Int): IO[List[Unit]] =
    List("scoped_events%", "scoped_states%").traverse { pattern =>
      for {
        result <- sql"""SELECT table_name from information_schema.tables where table_name like $pattern"""
                    .query[String]
                    .to[List]
                    .transact(xas.readCE)
        // We add +2 as there is the main table and the partition related to the organisation
        _       = result.assertSize(expected + 2)
      } yield ()
    }

  test("Create and update projects") {
    for {
      _ <- projects.create(active, fields)
      _ <- projects.update(active, 1, fields)
      _ <- projects.create(deprecated, fields)
      _ <- projects.deprecate(deprecated, 1)
      _ <- projects.create(markedAsDeleted, fields)
      _ <- projects.delete(markedAsDeleted, 1)
    } yield ()
  }

  test(s"Create dependencies between '$markedAsDeleted' and '$active'") {
    EntityDependencyStore.save(
      markedAsDeleted,
      entityToDelete,
      Set(
        DependsOn(active, nxv + "some-entity"),
        DependsOn(active, nxv + "some-other-entity")
      )
    )
  }

  test("Returned a noop instance when project deletion is disabled") {
    initCoordinator(deletionDisabled).map(_._2).assertEquals(ProjectDeletionCoordinator.Noop)
  }

  test("Run the deletion coordinator") {
    for {
      (deleted, c)      <- initCoordinator(deletionEnabled)
      _                 <- assertPartitions(3)
      // Running the coordinator
      activeCoordinator <- c match {
                             case Noop           => fail("We should have an active coordinator as deletion is enabled.")
                             case active: Active =>
                               active.run(Offset.start).compile.drain.as(active)
                           }
      // Checking that the deletion task has only be run for the expected project
      _                 <- deleted.get.assertEquals(Set(markedAsDeleted), s"The deletion task should only contain '$markedAsDeleted'.")
      // Checking that the deletion report has been saved
      savedReports      <- activeCoordinator.list(markedAsDeleted)
      _                  = savedReports.assertOneElem
      expectedReport     = ProjectDeletionReport(markedAsDeleted, Instant.EPOCH, Instant.EPOCH, subject, Vector(taskStage))
      _                  = savedReports.assertContains(expectedReport)
      // The project to be deleted should not be exist anymore while the others should remain
      _                 <- projects.fetch(active)
      _                 <- projects.fetch(deprecated)
      _                 <- projects.fetch(markedAsDeleted).intercept(ProjectNotFound(markedAsDeleted))
      // Checking that the partitions have been correctly deleted
      _                 <- assertPartitions(2)
      // Checking that the dependencies have been cleared
      _                 <- EntityDependencyStore
                             .directDependencies(markedAsDeleted, entityToDelete, xas)
                             .assertEquals(Set.empty[DependsOn])
    } yield ()
  }

}
