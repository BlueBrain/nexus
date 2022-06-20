package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectReferenceFinder.ProjectReferenceMap
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.ProjectGen._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.Deleting
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.{ProjectCreated, ProjectDeprecated, ProjectUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectFetchOptions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects._
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotaRejection.QuotaReached.{QuotaEventsReached, QuotaResourcesReached}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ProjectSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationIsDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectsBehaviors._
import ch.epfl.bluebrain.nexus.delta.sdk.{ProjectReferenceFinder, Projects, Quotas, QuotasDummy}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import com.datastax.oss.driver.api.core.uuid.Uuids
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

trait ProjectsBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues
    with Inspectors =>

  val epoch: Instant                 = Instant.EPOCH
  implicit val subject: Subject      = Identity.User("user", Label.unsafe("realm"))
  val serviceAccount: ServiceAccount = model.ServiceAccount(Identity.User("serviceAccount", Label.unsafe("realm")))

  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val orgUuid = UUID.randomUUID()

  val desc = Some("Project description")

  val mappings = ApiMappings(
    Map(
      "nxv" -> iri"https://localhost/nexus/vocabulary/",
      "rdf" -> iri"http://localhost/1999/02/22-rdf-syntax-ns#type"
    )
  )
  val base     = PrefixIri.unsafe(iri"https://localhost/base/")
  val voc      = PrefixIri.unsafe(iri"https://localhost/voc/")

  val payload        = ProjectFields(desc, mappings, Some(base), Some(voc))
  val anotherPayload = ProjectFields(Some("Another project description"), mappings, None, None)

  val org1 = Label.unsafe("org")
  val org2 = Label.unsafe("org2")

  // A project created on org1 has all owner permissions on / and org1
  val (rootPermissions, org1Permissions) =
    PermissionsGen.ownerPermissions.splitAt(PermissionsGen.ownerPermissions.size / 2)
  val proj10                             = Label.unsafe("proj10")

  // A project created on org2 lacks some of the owner permissions
  // proj20 has all owner permissions on /, org2 and proj20
  val proj20                               = Label.unsafe("proj20")
  val (org2Permissions, proj20Permissions) = org1Permissions.splitAt(org1Permissions.size / 2)

  // proj21 has some extra acls that should be preserved
  val proj21            = Label.unsafe("proj21")
  val proj21Permissions = Set(orgs.read, orgs.create)

  // proj22 has no permission set
  val proj22 = Label.unsafe("proj22")

  // this project must observe a cooldown before being created
  val projCoolDown = Label.unsafe("projCoolDown")

  val cooldown = epoch.plusSeconds(42 * 60L)

  def creationCooldown(proj: ProjectRef): IO[ProjectCreationCooldown, Unit] =
    IO.raiseWhen(proj == ProjectRef(org1, projCoolDown))(ProjectCreationCooldown(proj, cooldown))

  private val order = ResourceF.defaultSort[Project]

  val acls: Acls = null
//    AclSetup
//    .init(
//      (subject, AclAddress.Root, rootPermissions),
//      (subject, AclAddress.Organization(org1), org1Permissions),
//      (subject, AclAddress.Organization(org2), org2Permissions),
//      (subject, AclAddress.Project(org2, proj20), proj20Permissions),
//      (subject, AclAddress.Project(org2, proj21), proj21Permissions)
//    )
//    .accepted

  lazy val organizations: Organizations = null

  def create(quotas: Quotas): Task[Projects]

  lazy val projects: Projects = create(QuotasDummy.neverReached).accepted

  val ref: ProjectRef        = ProjectRef.unsafe("org", "proj")
  val anotherRef: ProjectRef = ProjectRef.unsafe("org2", "proj2")

  val anotherProjectReferences = ProjectReferenceMap.single(anotherRef, nxv + "id")

  implicit val referenceFinder: ProjectReferenceFinder = (project: ProjectRef) => {
    UIO.pure(
      project match {
        case `anotherRef` => anotherProjectReferences
        case _            => ProjectReferenceMap.empty
      }
    )
  }

  "The Projects operations bundle" should {

    "create a project" in {
      val project = projects.create(ref, payload).accepted

      project shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, payload),
        1L,
        subject
      )
    }

    val anotherProjResource = resourceFor(
      projectFromRef(anotherRef, uuid, orgUuid, markedForDeletion = false, anotherPayload),
      1L,
      Identity.Anonymous
    )

    "create another project" in {
      val project = projects.create(anotherRef, anotherPayload)(Identity.Anonymous).accepted

      project shouldEqual anotherProjResource
    }

    "not create a project if it already exists" in {
      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual ProjectAlreadyExists(ref)
    }

    "not create a project if its organization is deprecated" in {
      val ref = ProjectRef.unsafe("orgDeprecated", "proj")

      projects.create(ref, payload).rejectedWith[ProjectRejection] shouldEqual
        WrappedOrganizationRejection(OrganizationIsDeprecated(ref.organization))
    }

    "not create a project if a cooldown must be respected" in {
      val ref = ProjectRef(org1, projCoolDown)

      projects.create(ref, payload).rejected shouldEqual
        ProjectCreationCooldown(ref, cooldown)
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
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, newPayload),
        2L,
        subject
      )
    }

    "deprecate a project" in {
      projects.deprecate(ref, 2L).accepted shouldEqual resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, newPayload),
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

    "delete a project" in {
      projects.delete(ref, 3L).accepted shouldEqual Projects.uuidFrom(ref, epoch) -> resourceFor(
        projectFromRef(ref, uuid, orgUuid, markedForDeletion = true, newPayload),
        4L,
        subject,
        deprecated = true,
        markedForDeletion = true
      )
    }

    "not delete a project that has references" in {
      projects.delete(anotherRef, rev = 1L).rejected shouldEqual ProjectIsReferenced(
        anotherRef,
        anotherProjectReferences
      )
    }

    "fetch projects deletion status" in {
      val uuid   = Uuids.nameBased(Uuids.startOf(epoch.toEpochMilli), ref.toString)
      val status = ResourcesDeletionStatus(Deleting, ref, subject, epoch, subject, epoch, epoch, uuid)
      projects.fetchDeletionStatus.accepted shouldEqual SearchResults(1, List(status))

      projects.fetchDeletionStatus(ref, uuid).accepted shouldEqual status
    }

    "fail fetching project deletion status" in {
      projects.fetchDeletionStatus(ref, UUID.randomUUID()).rejectedWith[ProjectNotDeleted]
    }

    val resource = resourceFor(
      projectFromRef(ref, uuid, orgUuid, markedForDeletion = true, newPayload),
      4L,
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
      projects.fetchAt(ref, 1L).accepted shouldEqual
        resourceFor(projectFromRef(ref, uuid, orgUuid, markedForDeletion = false, payload), 1L, subject)
    }

    "fetch a project by uuid at a given revision" in {
      projects.fetchAt(uuid, 1L).accepted shouldEqual projects.fetchAt(ref, 1L).accepted
    }

    "fail fetching an unknown project" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetch(ref).rejectedWith[ProjectNotFound]
    }

    "fail fetching an unknown project with fetchProject" in {
      val ref = ProjectRef.unsafe("org", "unknown")

      projects.fetchProject(ref).rejectedWith[RejectionWrapper] shouldEqual
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

      projects.fetchAt(ref, 42L).rejectedWith[ProjectNotFound]
    }

    "fail fetching an unknown project by uuid at a given revision" in {
      projects.fetchAt(UUID.randomUUID(), 42L).rejectedWith[ProjectNotFound]
    }

    "fail fetching a project by uuid with the wrong orgUuid at a given revision" in {
      val unknownUuid = UUID.randomUUID()
      projects.fetchAt(unknownUuid, uuid, 1L).rejected shouldEqual ProjectNotFound(unknownUuid, uuid)
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

    val allEvents = SSEUtils.list(
      ref        -> ProjectCreated,
      anotherRef -> ProjectCreated,
      ref        -> ProjectUpdated,
      ref        -> ProjectDeprecated
    )

    "get the different events from start" in {
      val events = projects
        .events()
        .map { e => (e.event.project, e.eventType, e.offset) }
        .take(allEvents.size.toLong)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = projects
        .events(Sequence(2L))
        .map { e => (e.event.project, e.eventType, e.offset) }
        .take(2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from start" in {
      val events = projects
        .currentEvents()
        .map { e => (e.event.project, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from offset 2" in {
      val events = projects
        .currentEvents(Sequence(2L))
        .map { e => (e.event.project, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "fetch a project which has not been deprecated nor its organization" in {
      forAll(
        List(
          notDeprecatedOrDeleted,
          notDeprecatedOrDeletedWithQuotas,
          notDeprecatedOrDeletedWithEventQuotas,
          notDeprecatedOrDeletedWithResourceQuotas
        )
      ) { options =>
        projects.fetchProject(anotherRef, options).accepted shouldEqual anotherProjResource.value
      }
    }

    "not fetch a project with ProjectFetchOptions.VerifyQuotaResources" in {
      val ref      = ProjectRef.unsafe("org", "other")
      val projects = create(QuotasDummy.alwaysReached).accepted
      projects.create(ref, payload).accepted

      forAll(List(notDeprecatedOrDeletedWithQuotas, notDeprecatedOrDeletedWithResourceQuotas)) { options =>
        projects.fetchProject(ref, options).rejectedWith[RejectionWrapper] shouldEqual
          RejectionWrapper(WrappedQuotaRejection(QuotaResourcesReached(ref, 0)))
      }

      projects.fetchProject(ref, notDeprecatedOrDeletedWithEventQuotas).rejectedWith[RejectionWrapper] shouldEqual
        RejectionWrapper(WrappedQuotaRejection(QuotaEventsReached(ref, 0)))
    }

    "not fetch a deprecated project with ProjectFetchOptions.NotDeprecated" in {
      projects.fetchProject(ref, Set(NotDeprecated, VerifyQuotaResources)).rejectedWith[RejectionWrapper] shouldEqual
        RejectionWrapper(ProjectIsDeprecated(ref))
    }

    "not fetch a deleted project with ProjectFetchOptions.NotDeleted" in {
      projects.fetchProject(ref, Set(NotDeprecated, NotDeleted)).rejectedWith[RejectionWrapper] shouldEqual
        RejectionWrapper(ProjectIsMarkedForDeletion(ref))
    }

    "not fetch a project with a deprecated organization with fetchActive" in {
      val orgLabel   = Label.unsafe(genString())
      val projectRef = ProjectRef(orgLabel, Label.unsafe(genString()))

      (organizations.create(orgLabel, None) >>
        projects.create(projectRef, anotherPayload)(Identity.Anonymous)).accepted

      projects.fetchProject(projectRef, notDeprecatedOrDeleted).accepted.ref shouldEqual projectRef

      organizations.deprecate(orgLabel, 1).accepted

      projects.fetchProject(projectRef, notDeprecatedOrDeleted).rejected shouldEqual
        RejectionWrapper(WrappedOrganizationRejection(OrganizationIsDeprecated(orgLabel)))

      projects.fetchProject(projectRef, Set.empty).accepted
    }
  }

  "Creating projects" should {

    "set owner permissions on the project even if all permissions have been set on / and the org" in {
      val proj10Ref = ProjectRef(org1, proj10)
      projects.create(proj10Ref, payload).accepted.value.ref shouldEqual proj10Ref

      val resource = acls.fetch(AclAddress.Project(org1, proj10)).accepted
      resource.value.value shouldEqual Map(
        subject -> PermissionsGen.ownerPermissions
      )
      resource.rev shouldEqual 1L
    }

    "not set any permissions the project already has acls defined on the project address" in {
      val proj20Ref = ProjectRef(org2, proj20)
      projects.create(proj20Ref, payload).accepted.value.ref shouldEqual proj20Ref

      val resource = acls.fetch(AclAddress.Project(org2, proj20)).accepted
      resource.value.value shouldEqual Map(subject -> proj20Permissions)
      resource.rev shouldEqual 1L
    }
  }
}

object ProjectsBehaviors {
  final case class RejectionWrapper(projectRejection: ProjectRejection)

  implicit val rejectionMapper: Mapper[ProjectRejection, RejectionWrapper] =
    (value: ProjectRejection) => RejectionWrapper(value)
}
