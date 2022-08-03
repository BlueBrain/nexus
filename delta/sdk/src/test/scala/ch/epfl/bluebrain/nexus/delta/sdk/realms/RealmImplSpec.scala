package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.generators.RealmGen.{realm, resourceFor}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name, NonEmptySet, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IncorrectRev, RealmAlreadyDeprecated, RealmAlreadyExists, RealmNotFound, RealmOpenIdConfigAlreadyExists, RevisionNotFound, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.testkit.{DoobieScalaTestFixture, IOFixedClock, IOValues}
import monix.execution.Scheduler
import org.scalatest.CancelAfterFailure
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class RealmImplSpec
    extends DoobieScalaTestFixture
    with Matchers
    with IOValues
    with CancelAfterFailure
    with IOFixedClock
    with ConfigFixtures {

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))

  val githubLogo: Uri = "https://localhost/ghlogo"

  val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  val realmConfig = RealmsConfig(eventLogConfig, pagination, httpClientConfig)

  val resolveWellKnown = ioFromMap(
    Map(githubOpenId -> githubWk, gitlabOpenId -> gitlabWk),
    (uri: Uri) => UnsuccessfulOpenIdConfigResponse(uri)
  )

  lazy val realms = RealmsImpl(realmConfig, resolveWellKnown, xas)

  "Realms implementation" should {

    "create a realm" in {
      realms.create(github, githubName, githubOpenId, None, None).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            None
          ),
          1,
          subject
        )
    }

    "update a realm" in {
      realms
        .update(github, 1, githubName, githubOpenId, Some(githubLogo), Some(NonEmptySet.of("aud")))
        .accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo),
            Some(NonEmptySet.of("aud"))
          ),
          2,
          subject
        )
    }

    "deprecate a realm" in {
      realms.deprecate(github, 2).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo),
            Some(NonEmptySet.of("aud"))
          ),
          3,
          subject,
          deprecated = true
        )
    }

    "fetch a realm" in {
      realms.fetch(github).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo),
            Some(NonEmptySet.of("aud"))
          ),
          3,
          subject,
          deprecated = true
        )
    }

    "fetch a realm at specific revision" in {
      realms.fetchAt(github, 1).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            None
          ),
          1,
          subject
        )
    }

    "fail fetching a non existing realm" in {
      realms.fetch(Label.unsafe("non-existing")).rejectedWith[RealmNotFound]
    }

    "fail fetching a non existing realm at specific revision" in {
      realms.fetchAt(Label.unsafe("non-existing"), 1).rejectedWith[RealmNotFound]
    }

    "list realms" in {
      realms.create(gitlab, gitlabName, gitlabOpenId, None, None).accepted
      val ghRes = resourceFor(
        realm(
          githubOpenId,
          githubWk,
          Some(githubLogo),
          Some(NonEmptySet.of("aud"))
        ),
        3,
        subject,
        deprecated = true
      )
      val glRes = resourceFor(
        realm(
          gitlabOpenId,
          gitlabWk,
          None
        ),
        1,
        subject
      )
      val order = ResourceF.defaultSort[Realm]

      realms.list(FromPagination(0, 1), RealmSearchParams.none, order).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes)))
      realms.list(FromPagination(0, 10), RealmSearchParams.none, order).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes), UnscoredResultEntry(glRes)))
      val filter = RealmSearchParams(deprecated = Some(true), rev = Some(3), createdBy = Some(subject))
      realms.list(FromPagination(0, 10), filter, order).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(ghRes)))
    }

    "fail to fetch a realm on nonexistent revision" in {
      realms.fetchAt(github, 10).rejected shouldEqual RevisionNotFound(10, 3)
    }

    "fail to create a realm already created" in {
      realms.create(github, githubName, githubOpenId, None, None).rejectedWith[RealmAlreadyExists]
    }

    "fail to create with an existing openIdCongig" in {
      val label = Label.unsafe("duplicate")
      realms
        .create(label, githubName, githubOpenId, None, None)
        .rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
        RealmOpenIdConfigAlreadyExists(label, githubOpenId)
    }

    "fail to update a realm with incorrect rev" in {
      realms.update(gitlab, 3, gitlabName, gitlabOpenId, None, None).rejected shouldEqual IncorrectRev(3, 1)
    }

    "fail to update a realm a already used openId config" in {
      realms
        .update(gitlab, 1, githubName, githubOpenId, Some(githubLogo), Some(NonEmptySet.of("aud")))
        .rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
        RealmOpenIdConfigAlreadyExists(gitlab, githubOpenId)
    }

    "fail to deprecate a realm with incorrect rev" in {
      realms.deprecate(gitlab, 3).rejected shouldEqual IncorrectRev(3, 1)
    }

    "fail to update a non existing realm" in {
      realms.update(Label.unsafe("other"), 1, gitlabName, gitlabOpenId, None, None).rejectedWith[RealmNotFound]
    }

    "fail to deprecate a non existing realm" in {
      realms.deprecate(Label.unsafe("other"), 1).rejectedWith[RealmNotFound]
    }

    "fail to deprecate an already deprecated realm" in {
      realms.deprecate(github, 3).rejectedWith[RealmAlreadyDeprecated]
    }
  }

}
