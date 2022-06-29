package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{OrganizationIsDeprecated, OrganizationNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects.FetchOrganization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectsImplSpec.RejectionWrapper
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectMarkedForDeletion, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{IncorrectRev, ProjectAlreadyExists, ProjectIsDeprecated, ProjectNotFound, WrappedOrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, SSEUtils, ScopeInitializationLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock, IOValues}
import monix.bio.{IO, UIO}
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, OptionValues}

import java.util.UUID

class ProjectsImplSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with CancelAfterFailure
    with OptionValues
    with ConfigFixtures {

  implicit private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  implicit private val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  private val orgUuid = UUID.randomUUID()

  private val desc = Some("Project description")

  private val mappings = ApiMappings(
    Map(
      "nxv" -> iri"https://localhost/nexus/vocabulary/",
      "rdf" -> iri"http://localhost/1999/02/22-rdf-syntax-ns#type"
    )
  )
  private val base     = PrefixIri.unsafe(iri"https://localhost/base/")
  private val voc      = PrefixIri.unsafe(iri"https://localhost/voc/")

  private val payload        = ProjectFields(desc, mappings, Some(base), Some(voc))
  private val anotherPayload = ProjectFields(Some("Another project description"), mappings, None, None)

  private val org1          = Label.unsafe("org")
  private val org2          = Label.unsafe("org2")
  private val orgDeprecated = Label.unsafe("orgDeprecated")

  private val order = ResourceF.sortBy[Project]("_label").value

  private val config = ProjectsConfig(eventLogConfig, pagination, cacheConfig)

  private def fetchOrg: FetchOrganization = {
    case `org1`          => UIO.pure(Organization(org1, orgUuid, None))
    case `org2`          => UIO.pure(Organization(org2, orgUuid, None))
    case `orgDeprecated` => IO.raiseError(WrappedOrganizationRejection(OrganizationIsDeprecated(orgDeprecated)))
    case other           => IO.raiseError(WrappedOrganizationRejection(OrganizationNotFound(other)))
  }

  private lazy val (scopeInitLog, projects) = {
    for {
      scopeInitLog <- ScopeInitializationLog()
      projects     <- ProjectsImpl(fetchOrg, Set(scopeInitLog), defaultApiMappings, config, xas)
    } yield (scopeInitLog, projects)
  }.accepted

  private val ref: ProjectRef        = ProjectRef.unsafe("org", "proj")
  private val anotherRef: ProjectRef = ProjectRef.unsafe("org2", "proj2")

  "The Projects operations bundle" should {
    "create a project" in {
      val project = projects.create(ref, payload).accepted
      project shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, payload),
        1,
        subject
      )
      scopeInitLog.createdProjects.get.accepted shouldEqual Set(ref)
    }

    val anotherProjResource = resourceFor(
      projectFromRef(anotherRef, uuid, orgUuid, markedForDeletion = false, anotherPayload),
      1,
      Identity.Anonymous
    )

    "create another project" in {
      val project = projects.create(anotherRef, anotherPayload)(Identity.Anonymous).accepted

      project shouldEqual anotherProjResource

      scopeInitLog.createdProjects.get.accepted shouldEqual Set(ref, anotherRef)
    }

    "not create a project if it already exists" in {
      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual ProjectAlreadyExists(ref)
    }

    "not create a project if its organization is deprecated" in {
      val ref = ProjectRef.unsafe("orgDeprecated", "proj")

      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual
        WrappedOrganizationRejection(OrganizationIsDeprecated(ref.organization))
    }

    "not update a project if it doesn't exists" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.update(ref, 1, payload).rejectedWith[ProjectRejection] shouldEqual ProjectNotFound(ref)
    }

    "not update a project if a wrong revision is provided" in {
      projects.update(ref, 3, payload).rejectedWith[ProjectRejection] shouldEqual IncorrectRev(3, 1)
    }

    "not deprecate a project if it doesn't exists" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.deprecate(ref, 1).rejectedWith[ProjectRejection] shouldEqual ProjectNotFound(ref)
    }

    "not deprecate a project if a wrong revision is provided" in {
      projects.deprecate(ref, 3).rejectedWith[ProjectRejection] shouldEqual IncorrectRev(3, 1)
    }

    val newPayload = payload.copy(base = None, description = None)

    "update a project" in {
      projects.update(ref, 1, newPayload).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, newPayload),
        2,
        subject
      )
    }

    "deprecate a project" in {
      projects.deprecate(ref, 2).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, newPayload),
        3,
        subject,
        deprecated = true
      )
    }

    "not update a project if it has been already deprecated " in {
      projects.update(ref, 3, payload).rejectedWith[ProjectRejection] shouldEqual ProjectIsDeprecated(ref)
    }

    "not deprecate a project if it has been already deprecated " in {
      projects.deprecate(ref, 3).rejectedWith[ProjectRejection] shouldEqual ProjectIsDeprecated(ref)
    }

    "delete a project" in {
      projects.delete(ref, 3).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = true, newPayload),
        4,
        subject,
        deprecated = true,
        markedForDeletion = true
      )
    }

    val resource = resourceFor(
      projectFromRef(ref, uuid, orgUuid, markedForDeletion = true, newPayload),
      4,
      subject,
      deprecated = true,
      markedForDeletion = true
    )

    "fetch a project" in {
      projects.fetch(ref).accepted shouldEqual resource
    }

    "fetch a project with fetchProject" in {
      projects.fetchProject(ref).accepted shouldEqual resource.value
    }

    "fetch a project by uuid" in {
      projects.fetch(uuid).accepted shouldEqual projects.fetch(ref).accepted
    }

    "fetch a project at a given revision" in {
      projects.fetchAt(ref, 1).accepted shouldEqual
        resourceFor(projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, payload), 1, subject)
    }

    "fetch a project by uuid at a given revision" in {
      projects.fetchAt(uuid, 1).accepted shouldEqual projects.fetchAt(ref, 1).accepted
    }

    "fail fetching an unknown project" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetch(ref).rejectedWith[ProjectNotFound]
    }

    "fail fetching an unknown project with fetchProject" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetchProject[RejectionWrapper](ref).rejectedWith[RejectionWrapper] shouldEqual
        RejectionWrapper(ProjectNotFound(ref))
    }

    "fail fetching an unknown project by uuid" in {
      projects.fetch(UUID.randomUUID()).rejectedWith[ProjectNotFound]
    }

    "fail fetching a project by uuid with the wrong orgUuid" in {
      val unknownUuid = UUID.randomUUID()
      projects.fetch(unknownUuid, uuid).rejectedWith[ProjectNotFound]
    }

    "fail fetching an unknown project at a given revision" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetchAt(ref, 42).rejectedWith[ProjectNotFound]
    }

    "fail fetching an unknown project by uuid at a given revision" in {
      projects.fetchAt(UUID.randomUUID(), 42).rejectedWith[ProjectNotFound]
    }

    "fail fetching a project by uuid with the wrong orgUuid at a given revision" in {
      val unknownUuid = UUID.randomUUID()
      projects.fetchAt(unknownUuid, uuid, 1).rejected shouldEqual ProjectNotFound(unknownUuid, uuid)
    }

    "list projects without filters nor pagination" in {
      val results =
        projects.list(FromPagination(0, 10), ProjectSearchParams(filter = _ => UIO.pure(true)), order).accepted

      results shouldEqual SearchResults(2L, Vector(resource, anotherProjResource))
    }

    "list projects without filers but paginated" in {
      val results =
        projects.list(FromPagination(0, 1), ProjectSearchParams(filter = _ => UIO.pure(true)), order).accepted

      results shouldEqual SearchResults(2L, Vector(resource))
    }

    "list deprecated projects" in {
      val results =
        projects
          .list(
            FromPagination(0, 10),
            ProjectSearchParams(deprecated = Some(true), filter = _ => UIO.pure(true)),
            order
          )
          .accepted

      results shouldEqual SearchResults(1L, Vector(resource))
    }

    "list projects from organization org" in {
      val results =
        projects
          .list(
            FromPagination(0, 10),
            ProjectSearchParams(organization = Some(anotherRef.organization), filter = _ => UIO.pure(true)),
            order
          )
          .accepted

      results shouldEqual SearchResults(1L, Vector(anotherProjResource))
    }

    "list projects created by Anonymous" in {
      val results =
        projects
          .list(
            FromPagination(0, 10),
            ProjectSearchParams(createdBy = Some(Identity.Anonymous), filter = _ => UIO.pure(true)),
            order
          )
          .accepted

      results shouldEqual SearchResults(1L, Vector(anotherProjResource))
    }

    val allEvents = SSEUtils.extract(
      (ref, ProjectCreated, 1L),
      (anotherRef, ProjectCreated, 2L),
      (ref, ProjectUpdated, 3L),
      (ref, ProjectDeprecated, 4L),
      (ref, ProjectMarkedForDeletion, 5L)
    )

    "get the different events from start" in {
      val events = projects
        .events(Offset.Start)
        .map { e => (e.value.project, e.valueClass, e.offset) }
        .take(allEvents.size.toLong)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = projects
        .events(Offset.at(2L))
        .map { e => (e.value.project, e.valueClass, e.offset) }
        .take(3L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from start" in {
      val events = projects
        .currentEvents(Offset.Start)
        .map { e => (e.value.project, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from offset 2" in {
      val events = projects
        .currentEvents(Offset.at(2L))
        .map { e => (e.value.project, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }
  }
}

object ProjectsImplSpec {
  final case class RejectionWrapper(projectRejection: ProjectRejection)

  implicit val rejectionMapper: Mapper[ProjectRejection, RejectionWrapper] =
    (value: ProjectRejection) => RejectionWrapper(value)
}
