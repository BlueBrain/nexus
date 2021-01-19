package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.time.Instant
import akka.http.scaladsl.model.Uri
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.sdk.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.generators.RealmGen._
import ch.epfl.bluebrain.nexus.delta.sdk.generators.WellKnownGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent.{RealmCreated, RealmDeprecated, RealmUpdated}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{IncorrectRev, RealmAlreadyDeprecated, RealmAlreadyExists, RealmNotFound, RealmOpenIdConfigAlreadyExists, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, Name, ResourceF}
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, IOValues, TestHelpers}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.{CancelAfterFailure, OptionValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait RealmsBehaviors {
  this: AnyWordSpecLike
    with Matchers
    with IOValues
    with IOFixedClock
    with TestHelpers
    with CancelAfterFailure
    with OptionValues =>

  val epoch: Instant                = Instant.EPOCH
  implicit val subject: Subject     = Identity.User("user", Label.unsafe("realm"))
  implicit val scheduler: Scheduler = Scheduler.global
  implicit val baseUri: BaseUri     = BaseUri("http://localhost", Label.unsafe("v1"))

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
      realms.fetch(github).accepted shouldEqual
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
      realms.fetchAt(github, 1L).accepted shouldEqual
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

    "fail fetching a non existing realm" in {
      realms.fetch(Label.unsafe("non-existing")).rejectedWith[RealmNotFound]
    }

    "fail fetching a non existing realm at specific revision" in {
      realms.fetchAt(Label.unsafe("non-existing"), 1L).rejectedWith[RealmNotFound]
    }

    "list realms" in {
      realms.create(gitlab, gitlabName, gitlabOpenId, None).accepted
      val ghRes = resourceFor(
        realm(
          githubOpenId,
          githubWk,
          Some(githubLogo)
        ),
        3L,
        subject,
        deprecated = true
      )
      val glRes = resourceFor(
        realm(
          gitlabOpenId,
          gitlabWk,
          None
        ),
        1L,
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
      realms.fetchAt(github, 10L).rejected shouldEqual RevisionNotFound(10L, 3L)
    }

    "fail to create a realm already created" in {
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

    val allEvents = SSEUtils.list(
      (github -> RealmCreated),
      (github -> RealmUpdated),
      (github -> RealmDeprecated),
      (gitlab -> RealmCreated)
    )

    "get the different events from start" in {
      val events = realms
        .events()
        .map { e => (e.event.label, e.eventType, e.offset) }
        .take(4L)
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different current events from start" in {
      val events = realms
        .currentEvents()
        .map { e => (e.event.label, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents
    }

    "get the different events from offset 2" in {
      val events = realms
        .events(Sequence(2L))
        .map { e => (e.event.label, e.eventType, e.offset) }
        .take(2L)
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

    "get the different current events from offset 2" in {
      val events = realms
        .currentEvents(Sequence(2L))
        .map { e => (e.event.label, e.eventType, e.offset) }
        .compile
        .toList

      events.accepted shouldEqual allEvents.drop(2)
    }

  }

}
