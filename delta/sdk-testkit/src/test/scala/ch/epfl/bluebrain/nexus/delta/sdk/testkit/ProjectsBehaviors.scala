package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant
import java.util.UUID

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.{IncorrectRev, OrganizationIsDeprecated, ProjectAlreadyExists, ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{PrefixIRI, ProjectFields, ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.UIO
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait ProjectsBehaviors {
  this: AnyWordSpecLike with Matchers with IOValues with IOFixedClock with TestHelpers with OptionValues =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit val baseUri: BaseUri = BaseUri("http://localhost:8080/v1")

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val orgUuid = UUID.randomUUID()

  val desc = Some("Project description")

  val mappings = Map(
    "nxv" -> iri"https://localhost/nexus/vocabulary/",
    "rdf" -> iri"http://localhost/1999/02/22-rdf-syntax-ns#type"
  )
  val base     = PrefixIRI.unsafe(iri"https://localhost/base/")
  val voc      = PrefixIRI.unsafe(iri"https://localhost/voc/")

  val payload        = ProjectFields(desc, mappings, Some(base), Some(voc))
  val anotherPayload = ProjectFields(Some("Another project description"), mappings, None, None)

  val organizations: UIO[OrganizationsDummy] = {
    val orgUuidF: UUIDF = UUIDF.fixed(orgUuid)
    val orgs            = for {
      o <- OrganizationsDummy()(orgUuidF, ioClock)
      _ <- o.create(Label.unsafe("org"), None)
      _ <- o.create(Label.unsafe("orgDeprecated"), None)
      _ <- o.deprecate(Label.unsafe("orgDeprecated"), 1L)
    } yield o
    orgs.hideErrorsWith(r => new IllegalStateException(r.reason))
  }

  def create: UIO[Projects]

  val projects: Projects = create.accepted

  val ref = ProjectRef.unsafe("org", "proj")

  "The Projects operations bundle" should {

    "create a project" in {
      val project = projects.create(ref, payload).accepted

      project shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, payload),
        1L,
        subject
      )
    }

    "not create a project if it already exists" in {
      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual ProjectAlreadyExists(ref)
    }

    "not create a project if its organization is deprecated" in {
      val ref = ProjectRef.unsafe("orgDeprecated", "proj")

      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual OrganizationIsDeprecated(
        ref.organization
      )
    }

    "not update a project if it doesn't exists" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.update(ref, 1L, payload).rejectedWith[ProjectRejection] shouldEqual ProjectNotFound(ref)
    }

    "not update a project if a wrong revision is provided" in {
      projects.update(ref, 3L, payload).rejectedWith[ProjectRejection] shouldEqual IncorrectRev(3L, 1L)
    }

    "not deprecate a project if it doesn't exists" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.deprecate(ref, 1L).rejectedWith[ProjectRejection] shouldEqual ProjectNotFound(ref)
    }

    "not deprecate a project if a wrong revision is provided" in {
      projects.deprecate(ref, 3L).rejectedWith[ProjectRejection] shouldEqual IncorrectRev(3L, 1L)
    }

    val newPayload = payload.copy(base = None, description = None)

    "update a project" in {
      projects.update(ref, 1L, newPayload).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, newPayload),
        2L,
        subject
      )
    }

    "deprecate a project" in {
      projects.deprecate(ref, 2L).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, newPayload),
        3L,
        subject,
        deprecated = true
      )
    }

    "not update a project if it has been already deprecated " in {
      projects.update(ref, 3L, payload).rejectedWith[ProjectRejection] shouldEqual ProjectIsDeprecated(ref)
    }

    "not deprecate a project if it has been already deprecated " in {
      projects.deprecate(ref, 3L).rejectedWith[ProjectRejection] shouldEqual ProjectIsDeprecated(ref)
    }

    val deprecatedResource = resourceFor(
      projectFromRef(ref, uuid, orgUuid, newPayload),
      3L,
      subject,
      deprecated = true
    )

    "fetch a project" in {
      projects.fetch(ref).accepted.value shouldEqual deprecatedResource
    }

    "fetch a project by uuid" in {
      projects.fetch(uuid).accepted shouldEqual projects.fetch(ref).accepted
    }

    "fetch a project at a given revision" in {
      projects.fetchAt(ref, 1L).accepted.value shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, payload),
        1L,
        subject
      )
    }

    "fetch a project by uuid at a given revision" in {
      projects.fetchAt(uuid, 1L).accepted shouldEqual projects.fetchAt(ref, 1L).accepted
    }

    "fetch an unknown project" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetch(ref).accepted shouldEqual None
    }

    "fetch an unknown project by uuid" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetch(UUID.randomUUID()).accepted shouldEqual projects.fetch(ref).accepted
    }

    "fetch an unknown project at a given revision" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetchAt(ref, 42L).accepted shouldEqual None
    }

    "fetch an unknown project by uuid at a given revision" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetchAt(UUID.randomUUID(), 42L).accepted shouldEqual projects.fetchAt(ref, 42L).accepted
    }

    val anotherRef          = ProjectRef.unsafe("org", "proj2")
    val anotherProjResource = resourceFor(
      projectFromRef(anotherRef, uuid, orgUuid, anotherPayload),
      1L,
      Identity.Anonymous
    )

    "create another project" in {
      val project = projects.create(anotherRef, anotherPayload)(Identity.Anonymous).accepted

      project shouldEqual anotherProjResource
    }

    "list projects without filters nor pagination" in {
      val results = projects.list(FromPagination(0, 10)).accepted

      results shouldEqual SearchResults(2L, Vector(deprecatedResource, anotherProjResource))
    }

    "list projects without filers but paginated" in {
      val results = projects.list(FromPagination(0, 1)).accepted

      results shouldEqual SearchResults(2L, Vector(deprecatedResource))
    }

    "list deprecated projects" in {
      val results = projects.list(FromPagination(0, 10), ProjectSearchParams(deprecated = Some(true))).accepted

      results shouldEqual SearchResults(1L, Vector(deprecatedResource))
    }

    "list projects created by Anonymous" in {
      val results =
        projects.list(FromPagination(0, 10), ProjectSearchParams(createdBy = Some(Identity.Anonymous))).accepted

      results shouldEqual SearchResults(1L, Vector(anotherProjResource))
    }

    val allEvents = List(
      (ref, ClassUtils.simpleName(ProjectCreated), Sequence(1L)),
      (ref, ClassUtils.simpleName(ProjectUpdated), Sequence(2L)),
      (ref, ClassUtils.simpleName(ProjectDeprecated), Sequence(3L)),
      (anotherRef, ClassUtils.simpleName(ProjectCreated), Sequence(4L))
    )

    "get the different events from start" in {
      val events = projects
        .events()
        .map { e => (e.event.ref, e.eventType, e.offset) }
        .take(4L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = projects
        .events(Sequence(2L))
        .map { e => (e.event.ref, e.eventType, e.offset) }
        .take(2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from start" in {
      val events = projects
        .currentEvents()
        .map { e => (e.event.ref, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from offset 2" in {
      val events = projects
        .currentEvents(Sequence(2L))
        .map { e => (e.event.ref, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }
  }
}
