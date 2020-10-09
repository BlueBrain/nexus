package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.generators.RealmGen._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{IncorrectRev, RealmAlreadyDeprecated, RealmAlreadyExists, RealmNotFound, RealmOpenIdConfigAlreadyExists, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait RealmsBehaviors {
  this: AnyWordSpecLike with Matchers with IOValues with IOFixedClock with TestHelpers with OptionValues =>

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val scheduler: Scheduler = Scheduler.global

  val (github, gitlab)         = (Label.unsafe("github"), Label.unsafe("gitlab"))
  val (githubName, gitlabName) = (Name.unsafe("github-name"), Name.unsafe("gitlab-name"))

  val githubLogo: Uri = "https://localhost/ghlogo"

  val (githubOpenId, githubWk) = WellKnownGen.create(github.value)
  val (gitlabOpenId, gitlabWk) = WellKnownGen.create(gitlab.value)

  def create: Task[Realms]

  val realms = create.accepted

  "Realms implementation" should {

    "create a realm" in {
      realms.create(github, githubName, githubOpenId, None).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            None
          ),
          1L,
          subject
        )
    }

    "update a realm" in {
      realms.update(github, 1L, githubName, githubOpenId, Some(githubLogo)).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo)
          ),
          2L,
          subject
        )
    }

    "deprecate a realm" in {
      realms.deprecate(github, 2L).accepted shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo)
          ),
          3L,
          subject,
          deprecated = true
        )
    }

    "fetch a realm" in {
      realms.fetch(github).accepted.value shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            Some(githubLogo)
          ),
          3L,
          subject,
          deprecated = true
        )
    }

    "fetch a realm at specific revision" in {
      realms.fetchAt(github, 1L).accepted.value shouldEqual
        resourceFor(
          realm(
            githubOpenId,
            githubWk,
            None
          ),
          1L,
          subject
        )
    }

    "list realms" in {
      realms.create(gitlab, gitlabName, gitlabOpenId, None).accepted
      val ghRes  = resourceFor(
        realm(
          githubOpenId,
          githubWk,
          Some(githubLogo)
        ),
        3L,
        subject,
        deprecated = true
      )
      val glRes  = resourceFor(
        realm(
          gitlabOpenId,
          gitlabWk,
          None
        ),
        1L,
        subject
      )
      realms.list(FromPagination(0, 1)).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes)))
      realms.list(FromPagination(0, 10)).accepted shouldEqual
        UnscoredSearchResults(2L, Vector(UnscoredResultEntry(ghRes), UnscoredResultEntry(glRes)))
      val filter =
        RealmSearchParams(deprecated = Some(true), rev = Some(3), createdBy = Some(subject), updatedBy = Some(subject))
      realms.list(FromPagination(0, 10), filter).accepted shouldEqual
        UnscoredSearchResults(1L, Vector(UnscoredResultEntry(ghRes)))
    }

    "fail to fetch a realm on nonexistent revision" in {
      realms.fetchAt(github, 10L).rejected shouldEqual RevisionNotFound(10L, 3L)
    }

    "fail to create a real already created" in {
      realms.create(github, githubName, githubOpenId, None).rejectedWith[RealmAlreadyExists]
    }

    "fail to create with an existing openIdCongig" in {
      val label = Label.unsafe("duplicate")
      realms.create(label, githubName, githubOpenId, None).rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
        RealmOpenIdConfigAlreadyExists(label, githubOpenId)
    }

    "fail to update a realm with incorrect rev" in {
      realms.update(gitlab, 3L, gitlabName, gitlabOpenId, None).rejected shouldEqual IncorrectRev(3L, 1L)
    }

    "fail to update a realm a already used openId config" in {
      realms
        .update(gitlab, 1L, githubName, githubOpenId, Some(githubLogo))
        .rejectedWith[RealmOpenIdConfigAlreadyExists] shouldEqual
        RealmOpenIdConfigAlreadyExists(gitlab, githubOpenId)
    }

    "fail to deprecate a realm with incorrect rev" in {
      realms.deprecate(gitlab, 3L).rejected shouldEqual IncorrectRev(3L, 1L)
    }

    "fail to update a non existing realm" in {
      realms.update(Label.unsafe("other"), 1L, gitlabName, gitlabOpenId, None).rejectedWith[RealmNotFound]
    }

    "fail to deprecate a non existing realm" in {
      realms.deprecate(Label.unsafe("other"), 1L).rejectedWith[RealmNotFound]
    }

    "fail to deprecate an already deprecated realm" in {
      realms.deprecate(github, 3L).rejectedWith[RealmAlreadyDeprecated]
    }

  }

}
