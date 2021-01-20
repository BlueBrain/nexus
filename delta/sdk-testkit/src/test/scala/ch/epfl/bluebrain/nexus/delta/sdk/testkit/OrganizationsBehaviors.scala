package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant
import java.util.UUID
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.sdk.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, realms}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait OrganizationsBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues =>

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  val serviceAccount: Subject   = Identity.User("serviceAccount", Label.unsafe("realm"))

  implicit val scheduler: Scheduler = Scheduler.global

  val description  = Some("my description")
  val description2 = Some("my other description")
  val label        = Label.unsafe("myorg")
  val label2       = Label.unsafe("myorg2")
  val uuid         = UUID.randomUUID()

  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  // myorg has all owner permissions on / and itself
  val (rootPermissions, myOrgPermissions) =
    PermissionsGen.ownerPermissions.splitAt(PermissionsGen.ownerPermissions.size / 2)
  // myorg2 misses some owner permissions and have other ones that should not be deleted
  val myOrg2Permissions                   = Set(events.read, realms.read)

  val acls = {
    for {
      acls <- AclsDummy(PermissionsDummy(PermissionsGen.minimum))
      _    <- acls.append(Acl(AclAddress.Root, subject -> rootPermissions), 0L)(serviceAccount)
      _    <- acls.append(Acl(AclAddress.Organization(label), subject -> myOrgPermissions), 0L)(serviceAccount)
      _    <- acls.append(Acl(AclAddress.Organization(label2), subject -> myOrg2Permissions), 0L)(serviceAccount)
    } yield acls
  }.accepted

  def create: Task[Organizations]

  val orgs = create.accepted

  "Organizations implementation" should {

    "create an organization" in {
      orgs.create(label, description).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1L, subject)

      acls.fetch(AclAddress.Organization(label)).accepted.value.value shouldEqual Map(subject -> myOrgPermissions)
    }

    "update an organization" in {
      orgs.update(label, description2, 1L).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 2L, subject)
    }

    "deprecate an organization" in {
      orgs.deprecate(label, 2L).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 3L, subject, deprecated = true)
    }

    "fetch an organization" in {
      orgs.fetch(label).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 3L, subject, deprecated = true)
    }

    "fetch an organization by uuid" in {
      orgs.fetch(uuid).accepted shouldEqual orgs.fetch(label).accepted
    }

    "fetch an organization at specific revision" in {
      orgs.fetchAt(label, 1L).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1L, subject)
    }

    "fetch an organization at specific revision by uuid" in {
      orgs.fetchAt(uuid, 1L).accepted shouldEqual orgs.fetchAt(label, 1L).accepted
    }

    "fail fetching a non existing organization" in {
      orgs.fetch(Label.unsafe("non-existing")).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization by uuid" in {
      orgs.fetch(UUID.randomUUID()).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization at specific revision" in {
      orgs.fetchAt(Label.unsafe("non-existing"), 1L).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization at specific revision by uuid" in {
      orgs.fetchAt(UUID.randomUUID(), 1L).rejectedWith[OrganizationNotFound]
    }

    "create another organization" in {
      orgs.create(label2, None).accepted

      acls.fetch(AclAddress.Organization(label2)).accepted.value.value shouldEqual
        Map(subject -> (PermissionsGen.ownerPermissions ++ myOrg2Permissions))
    }

    "list organizations" in {
      val result1 = orgs.fetch(label).accepted
      val result2 = orgs.fetch(label2).accepted
      val filter  = OrganizationSearchParams(deprecated = Some(true), rev = Some(3), filter = _ => true)
      val order   = ResourceF.defaultSort[Organization]

      orgs.list(FromPagination(0, 1), OrganizationSearchParams(filter = _ => true), order).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1)))
      orgs.list(FromPagination(0, 10), OrganizationSearchParams(filter = _ => true), order).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1), UnscoredResultEntry(result2)))
      orgs.list(FromPagination(0, 10), filter, order).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(result1)))
    }

    val allEvents = SSEUtils.list(
      label  -> OrganizationCreated,
      label  -> OrganizationUpdated,
      label  -> OrganizationDeprecated,
      label2 -> OrganizationCreated
    )

    "get the different events from start" in {
      val events = orgs
        .events()
        .map { e => (e.event.label, e.eventType, e.offset) }
        .take(4L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = orgs
        .events(Sequence(2L))
        .map { e => (e.event.label, e.eventType, e.offset) }
        .take(2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from start" in {
      val events = orgs
        .currentEvents()
        .map { e => (e.event.label, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from offset 2" in {
      val events = orgs
        .currentEvents(Sequence(2L))
        .map { e => (e.event.label, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "fail to fetch an organization on nonexistent revision" in {
      orgs.fetchAt(label, 10L).rejected shouldEqual RevisionNotFound(10L, 3L)
    }

    "fail to create an organization already created" in {
      orgs.create(label, None).rejectedWith[OrganizationAlreadyExists]
    }

    "fail to update an organization with incorrect rev" in {
      orgs.update(label, None, 1L).rejected shouldEqual IncorrectRev(1L, 3L)
    }

    "fail to deprecate an organization with incorrect rev" in {
      orgs.deprecate(label, 1L).rejected shouldEqual IncorrectRev(1L, 3L)
    }

    "fail to update a non existing organization" in {
      orgs.update(Label.unsafe("other"), None, 1L).rejectedWith[OrganizationNotFound]
    }

    "fail to deprecate a non existing organization" in {
      orgs.deprecate(Label.unsafe("other"), 1L).rejectedWith[OrganizationNotFound]
    }

    "fail to deprecate an already deprecated organization" in {
      orgs.deprecate(label, 3L).rejectedWith[OrganizationIsDeprecated]
    }

  }

}
