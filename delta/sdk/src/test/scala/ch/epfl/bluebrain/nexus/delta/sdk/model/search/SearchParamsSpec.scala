package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.sdk.generators.{OrganizationGen, ProjectGen, RealmGen, WellKnownGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.{OrganizationSearchParams, ProjectSearchParams, RealmSearchParams}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.bio.UIO
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SearchParamsSpec extends AnyWordSpecLike with IOValues with Matchers with Inspectors {

  private val subject = User("myuser", Label.unsafe("myrealm"))

  "A RealmSearchParams" should {
    val issuer              = "myrealm"
    val (wellKnownUri, wk)  = WellKnownGen.create(issuer)
    val resource            = RealmGen.resourceFor(RealmGen.realm(wellKnownUri, wk, None), 1, subject)
    val searchWithAllParams = RealmSearchParams(
      issuer = Some(issuer),
      deprecated = Some(false),
      rev = Some(1L),
      createdBy = Some(subject),
      updatedBy = Some(subject),
      r => UIO.pure(r.name == resource.value.name)
    )

    "match a realm resource" in {
      forAll(List(searchWithAllParams, RealmSearchParams(), RealmSearchParams(issuer = Some(issuer)))) { search =>
        search.matches(resource).accepted shouldEqual true
      }
    }

    "not match a realm resource" in {
      forAll(
        List(
          resource.copy(deprecated = true),
          resource.copy(rev = 2L),
          resource.map(_.copy(issuer = "other")),
          resource.map(_.copy(name = Name.unsafe("other")))
        )
      ) { resource =>
        searchWithAllParams.matches(resource).accepted shouldEqual false
      }
    }
  }

  "An OrganizationSearchParams" should {
    val searchWithAllParams = OrganizationSearchParams(
      deprecated = Some(false),
      rev = Some(1L),
      createdBy = Some(subject),
      updatedBy = Some(subject),
      label = Some("myorg"),
      _ => UIO.pure(true)
    )
    val resource            = OrganizationGen.resourceFor(OrganizationGen.organization("myorg"), 1, subject)

    "match an organization resource" in {
      forAll(
        List(
          searchWithAllParams,
          OrganizationSearchParams(label = Some("my"), filter = _ => UIO.pure(true)),
          OrganizationSearchParams(filter = _ => UIO.pure(true)),
          OrganizationSearchParams(rev = Some(1L), filter = _ => UIO.pure(true))
        )
      ) { search =>
        search.matches(resource).accepted shouldEqual true
      }
    }

    "not match an organization resource" in {
      forAll(
        List(
          resource.map(_.copy(label = Label.unsafe("other"))),
          resource.copy(deprecated = true),
          resource.copy(createdBy = Anonymous)
        )
      ) { resource =>
        searchWithAllParams.matches(resource).accepted shouldEqual false
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
      updatedBy = Some(subject),
      label = Some("myproj"),
      _ => UIO.pure(true)
    )
    val resource            = ProjectGen.resourceFor(ProjectGen.project("myorg", "myproj"), 1, subject)

    "match a project resource" in {
      forAll(
        List(
          searchWithAllParams,
          ProjectSearchParams(label = Some("my"), filter = _ => UIO.pure(true)),
          ProjectSearchParams(filter = _ => UIO.pure(true)),
          ProjectSearchParams(rev = Some(1L), filter = _ => UIO.pure(true))
        )
      ) { search =>
        search.matches(resource).accepted shouldEqual true
      }
    }

    "not match a project resource" in {
      forAll(
        List(
          resource.copy(deprecated = true),
          resource.map(_.copy(label = Label.unsafe("o"))),
          resource.map(_.copy(organizationLabel = Label.unsafe("o")))
        )
      ) { resource =>
        searchWithAllParams.matches(resource).accepted shouldEqual false
      }
    }
  }

}
