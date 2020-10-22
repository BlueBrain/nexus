package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen, RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.{OrganizationSearchParams, ProjectSearchParams, RealmSearchParams}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SearchParamsSpec extends AnyWordSpecLike with Matchers with Inspectors {

  private val subject = User("myuser", Label.unsafe("myrealm"))

  "A RealmSearchParams" should {
    val issuer              = "myrealm"
    val (wellKnownUri, wk)  = WellKnownGen.create(issuer)
    val resource            = RealmGen.resourceFor(RealmGen.realm(wellKnownUri, wk, None), 1L, subject)
    val searchWithAllParams = RealmSearchParams(
      issuer = Some(issuer),
      deprecated = Some(false),
      rev = Some(1L),
      createdBy = Some(subject),
      updatedBy = Some(subject)
    )

    "match a realm resource" in {
      forAll(List(searchWithAllParams, RealmSearchParams(), RealmSearchParams(issuer = Some(issuer)))) { search =>
        search.matches(resource) shouldEqual true
      }
    }

    "not match a realm resource" in {
      forAll(List(resource.copy(deprecated = true), resource.copy(rev = 2L), resource.map(_.copy(issuer = "other")))) {
        resource =>
          searchWithAllParams.matches(resource) shouldEqual false
      }
    }
  }

  "An OrganizationSearchParams" should {
    val searchWithAllParams = OrganizationSearchParams(
      deprecated = Some(false),
      rev = Some(1L),
      createdBy = Some(subject),
      updatedBy = Some(subject)
    )
    val resource            = OrganizationGen.resourceFor(OrganizationGen.organization("myorg"), 1L, subject)

    "match an organization resource" in {
      forAll(List(searchWithAllParams, OrganizationSearchParams(), OrganizationSearchParams(rev = Some(1L)))) {
        search =>
          search.matches(resource) shouldEqual true
      }
    }

    "not match an organization resource" in {
      forAll(List(resource.copy(deprecated = true), resource.copy(createdBy = Anonymous))) { resource =>
        searchWithAllParams.matches(resource) shouldEqual false
      }
    }
  }

  "A ProjectSearchParams" should {
    val org                 = Label.unsafe("myorg")
    val searchWithAllParams = ProjectSearchParams(
      organization = Some(org),
      deprecated = Some(false),
      rev = Some(1L),
      createdBy = Some(subject),
      updatedBy = Some(subject)
    )
    val resource            = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproj"), 1L, subject)

    "match a project resource" in {
      forAll(List(searchWithAllParams, ProjectSearchParams(), ProjectSearchParams(rev = Some(1L)))) { search =>
        search.matches(resource) shouldEqual true
      }
    }

    "not match a project resource" in {
      forAll(List(resource.copy(deprecated = true), resource.map(_.copy(organizationLabel = Label.unsafe("o"))))) {
        resource =>
          searchWithAllParams.matches(resource) shouldEqual false
      }
    }
  }

}
