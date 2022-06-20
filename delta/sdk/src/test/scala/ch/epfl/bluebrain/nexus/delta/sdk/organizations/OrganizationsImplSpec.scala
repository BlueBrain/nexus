package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen.{organization, resourceFor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent.{OrganizationCreated, OrganizationDeprecated, OrganizationUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{IncorrectRev, OrganizationAlreadyExists, OrganizationIsDeprecated, OrganizationNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, SSEUtils, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock, IORef, IOValues}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, OptionValues}

import java.time.Instant
import java.util.UUID

class OrganizationsImplSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with IOFixedClock
    with CancelAfterFailure
    with OptionValues
    with ConfigFixtures {

  private lazy val config = OrganizationsConfig(eventLogConfig, pagination, 10)

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  implicit val scheduler: Scheduler = Scheduler.global

  val description  = Some("my description")
  val description2 = Some("my other description")
  val label        = Label.unsafe("myorg")
  val label2       = Label.unsafe("myorg2")

  private lazy val (orgLabels, orgs) = {
    for {
      orgLabels <- IORef.of(Set.empty[Label])
      orgs      <- OrganizationsImpl(
                     Set(new ScopeInitialization {
                       override def onOrganizationCreation(
                           organization: Organization,
                           subject: Subject
                       ): IO[ServiceError.ScopeInitializationFailed, Unit] =
                         orgLabels.update(_ + organization.label)

                       override def onProjectCreation(
                           project: Project,
                           subject: Subject
                       ): IO[ServiceError.ScopeInitializationFailed, Unit] = IO.unit
                     }),
                     config,
                     xas
                   )
    } yield (orgLabels, orgs)
  }.accepted

  "Organizations implementation" should {

    "create an organization" in {
      orgs.create(label, description).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1, subject)
      orgLabels.get.accepted shouldEqual Set(label)
    }

    "update an organization" in {
      orgs.update(label, description2, 1).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 2, subject)
    }

    "deprecate an organization" in {
      orgs.deprecate(label, 2).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 3, subject, deprecated = true)
    }

    "fetch an organization" in {
      orgs.fetch(label).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description2), 3, subject, deprecated = true)
    }

    "fetch an organization by uuid" in {
      orgs.fetch(uuid).accepted shouldEqual orgs.fetch(label).accepted
    }

    "fetch an organization at specific revision" in {
      orgs.fetchAt(label, 1).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1, subject)
    }

    "fetch an organization at specific revision by uuid" in {
      orgs.fetchAt(uuid, 1).accepted shouldEqual orgs.fetchAt(label, 1).accepted
    }

    "fail fetching a non existing organization" in {
      orgs.fetch(Label.unsafe("non-existing")).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization by uuid" in {
      orgs.fetch(UUID.randomUUID()).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization at specific revision" in {
      orgs.fetchAt(Label.unsafe("non-existing"), 1).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization at specific revision by uuid" in {
      orgs.fetchAt(UUID.randomUUID(), 1).rejectedWith[OrganizationNotFound]
    }

    "create another organization" in {
      orgs.create(label2, None).accepted

      orgLabels.get.accepted shouldEqual Set(label, label2)
    }

    "list organizations" in {
      val result1 = orgs.fetch(label).accepted
      val result2 = orgs.fetch(label2).accepted
      val filter  = OrganizationSearchParams(deprecated = Some(true), rev = Some(3), filter = _ => UIO.pure(true))
      val order   = ResourceF.defaultSort[Organization]

      orgs
        .list(FromPagination(0, 1), OrganizationSearchParams(filter = _ => UIO.pure(true)), order)
        .accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1)))
      orgs
        .list(FromPagination(0, 10), OrganizationSearchParams(filter = _ => UIO.pure(true)), order)
        .accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1), UnscoredResultEntry(result2)))
      orgs.list(FromPagination(0, 10), filter, order).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(result1)))
    }

    val allEvents = SSEUtils.extract(
      (label, OrganizationCreated, 1L),
      (label, OrganizationUpdated, 2L),
      (label, OrganizationDeprecated, 3L),
      (label2, OrganizationCreated, 4L)
    )

    "get the different events from start" in {
      val events = orgs
        .events()
        .map { e => (e.value.label, e.valueClass, e.offset) }
        .take(4L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = orgs
        .events(Offset.at(2L))
        .map { e => (e.value.label, e.valueClass, e.offset) }
        .take(2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from start" in {
      val events = orgs
        .currentEvents()
        .map { e => (e.value.label, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from offset 2" in {
      val events = orgs
        .currentEvents(Offset.at(2L))
        .map { e => (e.value.label, e.valueClass, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "fail to fetch an organization on nonexistent revision" in {
      orgs.fetchAt(label, 10).rejected shouldEqual RevisionNotFound(10, 3)
    }

    "fail to create an organization already created" in {
      orgs.create(label, None).rejectedWith[OrganizationAlreadyExists]
    }

    "fail to update an organization with incorrect rev" in {
      orgs.update(label, None, 1).rejected shouldEqual IncorrectRev(1, 3)
    }

    "fail to deprecate an organization with incorrect rev" in {
      orgs.deprecate(label, 1).rejected shouldEqual IncorrectRev(1, 3)
    }

    "fail to update a non existing organization" in {
      orgs.update(Label.unsafe("other"), None, 1).rejectedWith[OrganizationNotFound]
    }

    "fail to deprecate a non existing organization" in {
      orgs.deprecate(Label.unsafe("other"), 1).rejectedWith[OrganizationNotFound]
    }

    "fail to deprecate an already deprecated organization" in {
      orgs.deprecate(label, 3).rejectedWith[OrganizationIsDeprecated]
    }

  }
}
