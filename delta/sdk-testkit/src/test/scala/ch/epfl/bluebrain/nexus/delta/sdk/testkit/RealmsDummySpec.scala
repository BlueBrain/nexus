package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, Realm, WellKnown}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label, Name, ResourceF}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOFixedClock, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class RealmsDummySpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with TestHelpers
    with IOFixedClock
    with CirceLiteral
    with OptionValues
    with Inspectors {

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val scheduler: Scheduler = Scheduler.global

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))
  val ghBase: Uri              = "https://example.com/auth/realms/github/protocol/openid-connect/"
  val glBase: Uri              = "https://example.com/auth/realms/gitlab/protocol/openid-connect/"
  val ghOpenId: Uri            = "https://example.com/auth/realms/github/.well-known/openid-configuration"
  val glOpenId: Uri            = "https://example.com/auth/realms/gitlab/.well-known/openid-configuration"
  val gt: Set[GrantType]       = Set(GrantType.AuthorizationCode, GrantType.Implicit)
  val (ghKeys, glKeys)         = (Set(json"""{ "k": "github" }"""), Set(json"""{ "k": "gitlab" }"""))
  val ghlogo: Uri              = "https://example.com/ghlogo"
  // format: off
  val githubWk                 = WellKnown(github.value, gt, ghKeys, ghOpenId, s"$ghBase/token", s"$ghBase/userinfo", None, None)
  val gitlabWk                 = WellKnown(gitlab.value, gt, glKeys, glOpenId, s"$glBase/token", s"$glBase/userinfo", None, None)
  // format: on

  val dummy = RealmsDummy(Map(ghOpenId -> githubWk, glOpenId -> gitlabWk)).accepted

  def resourceFor(
      openIdConfig: Uri,
      logo: Option[Uri],
      rev: Long,
      wk: WellKnown
  ): RealmResource = {
    // format: off
    val realm = Realm(Label.unsafe(wk.issuer), Name.unsafe(s"${wk.issuer}-name"), openIdConfig, wk.issuer, wk.grantTypes, logo, wk.authorizationEndpoint, wk.tokenEndpoint, wk.userInfoEndpoint, wk.revocationEndpoint, wk.endSessionEndpoint, wk.keys)
    // format: on
    ResourceF(
      id = Label.unsafe(wk.issuer),
      rev = rev,
      types = Set(nxv.Realm),
      deprecated = false,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schemas.realms),
      value = realm
    )
  }

  "A dummy Realms implementation" should {

    "create a realm" in {
      dummy.create(github, githubName, ghOpenId, None).accepted shouldEqual
        resourceFor(ghOpenId, None, 1L, githubWk)
    }

    "update a realm" in {
      dummy.update(github, 1L, githubName, ghOpenId, Some(ghlogo)).accepted shouldEqual
        resourceFor(ghOpenId, Some(ghlogo), 2L, githubWk)
    }

    "deprecate a realm" in {
      dummy.deprecate(github, 2L).accepted shouldEqual
        resourceFor(ghOpenId, Some(ghlogo), 3L, githubWk).copy(deprecated = true)
    }

    "fetch a realm" in {
      dummy.fetch(github).accepted.value shouldEqual
        resourceFor(ghOpenId, Some(ghlogo), 3L, githubWk).copy(deprecated = true)
    }

    "fetch a realm at specific revision" in {
      dummy.fetchAt(github, 1L).accepted.value shouldEqual
        resourceFor(ghOpenId, None, 1L, githubWk)
    }

    "list realms" in {
      dummy.create(gitlab, gitlabName, glOpenId, None).accepted
      val ghRes  = resourceFor(ghOpenId, Some(ghlogo), 3L, githubWk).copy(deprecated = true)
      val glRes  = resourceFor(glOpenId, None, 1L, gitlabWk)
      dummy.list(FromPagination(0, 1)).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes)))
      dummy.list(FromPagination(0, 10)).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes), UnscoredResultEntry(glRes)))
      val filter =
        RealmSearchParams(deprecated = Some(true), rev = Some(3), createdBy = Some(subject), updatedBy = Some(subject))
      dummy.list(FromPagination(0, 10), filter).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(ghRes)))
    }

    "fail to fetch a realm on nonexistent revision" in {
      dummy.fetchAt(github, 10L).rejected shouldEqual RevisionNotFound(10L, 3L)
    }

    "fail to create a real already created" in {
      dummy.create(github, githubName, ghOpenId, None).rejectedWith[RealmAlreadyExists]
    }

    "fail to update a realm with incorrect rev" in {
      dummy.update(gitlab, 3L, gitlabName, glOpenId, None).rejected shouldEqual IncorrectRev(3L, 1L)
    }

    "fail to deprecate a realm with incorrect rev" in {
      dummy.deprecate(gitlab, 3L).rejected shouldEqual IncorrectRev(3L, 1L)
    }

    "fail to update a non existing realm" in {
      dummy.update(Label.unsafe("other"), 1L, gitlabName, glOpenId, None).rejectedWith[RealmNotFound]
    }

    "fail to deprecate a non existing realm" in {
      dummy.deprecate(Label.unsafe("other"), 1L).rejectedWith[RealmNotFound]
    }

    "fail to deprecate an already deprecated realm" in {
      dummy.deprecate(github, 3L).rejectedWith[RealmAlreadyDeprecated]
    }

  }

}
