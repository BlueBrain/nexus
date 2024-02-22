package ch.epfl.bluebrain.nexus.delta.sdk.organizations

import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.generators.OrganizationGen.{organization, resourceFor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.{IncorrectRev, OrganizationAlreadyExists, OrganizationIsDeprecated, OrganizationNotFound, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.{ConfigFixtures, OrganizationResource, ScopeInitializer}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.DoobieScalaTestFixture
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import org.scalatest.CancelAfterFailure

import java.time.Instant
import java.util.UUID

class OrganizationsImplSpec
    extends CatsEffectSpec
    with DoobieScalaTestFixture
    with CancelAfterFailure
    with ConfigFixtures {

  val uuid                  = UUID.randomUUID()
  implicit val uuidF: UUIDF = UUIDF.fixed(uuid)

  val epoch: Instant            = Instant.EPOCH
  implicit val subject: Subject = Identity.User("user", Label.unsafe("realm"))

  val description  = Some("my description")
  val description2 = Some("my other description")
  val label        = Label.unsafe("myorg")
  val label2       = Label.unsafe("myorg2")

  private def orgInitializer(wasExecuted: Ref[IO, Boolean]) = new ScopeInitializer {
    override def initializeOrganization(organizationResource: OrganizationResource)(implicit
        caller: Subject
    ): IO[Unit] = wasExecuted.set(true)

    override def initializeProject(project: ProjectRef)(implicit caller: Subject): IO[Unit] =
      IO.unit
  }

  private lazy val orgs = OrganizationsImpl(ScopeInitializer.noop, eventLogConfig, xas, clock)

  "Organizations implementation" should {

    "create an organization" in {
      orgs.create(label, description).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1, subject)
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

    "fetch an organization at specific revision" in {
      orgs.fetchAt(label, 1).accepted shouldEqual
        resourceFor(organization("myorg", uuid, description), 1, subject)
    }

    "fail fetching a non existing organization" in {
      orgs.fetch(Label.unsafe("non-existing")).rejectedWith[OrganizationNotFound]
    }

    "fail fetching a non existing organization at specific revision" in {
      orgs.fetchAt(Label.unsafe("non-existing"), 1).rejectedWith[OrganizationNotFound]
    }

    "create another organization" in {
      orgs.create(label2, None).accepted
    }

    "list organizations" in {
      val result1 = orgs.fetch(label).accepted
      val result2 = orgs.fetch(label2).accepted
      val filter  = OrganizationSearchParams(deprecated = Some(true), rev = Some(3), filter = _ => IO.pure(true))
      val order   = ResourceF.defaultSort[Organization]

      orgs
        .list(FromPagination(0, 1), OrganizationSearchParams(filter = _ => IO.pure(true)), order)
        .accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1)))
      orgs
        .list(FromPagination(0, 10), OrganizationSearchParams(filter = _ => IO.pure(true)), order)
        .accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(result1), UnscoredResultEntry(result2)))
      orgs.list(FromPagination(0, 10), filter, order).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(result1)))
    }

    "run the initializer upon organization creation" in {
      val initializerWasExecuted = Ref.unsafe[IO, Boolean](false)
      val orgs                   = OrganizationsImpl(orgInitializer(initializerWasExecuted), eventLogConfig, xas, clock)

      orgs.create(Label.unsafe(genString()), description).accepted
      initializerWasExecuted.get.accepted shouldEqual true
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
