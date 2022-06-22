package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestCommand._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestTagged}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestRejection.{AlreadyExists, NotFound, PullRequestAlreadyClosed, RevisionNotFound}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.{PullRequestCommand, PullRequestEvent, PullRequestState}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.testkit.{DoobieFixture, MonixBioSuite}

import java.time.Instant
import scala.concurrent.duration._

class ScopedEventLogSuite extends MonixBioSuite with DoobieFixture {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Delay(500.millis))

  private lazy val eventStore = ScopedEventStore(
    PullRequest.entityType,
    PullRequestEvent.serializer,
    queryConfig,
    xas
  )

  private lazy val stateStore = ScopedStateStore(
    PullRequest.entityType,
    PullRequestState.serializer,
    queryConfig,
    xas
  )

  private val maxDuration = 100.millis

  private val id   = Label.unsafe("id")
  private val proj = ProjectRef.unsafe("org", "proj")

  private val opened = PullRequestCreated(id, proj, Instant.EPOCH, Anonymous)
  private val tagged = PullRequestTagged(id, proj, 2, 1, Instant.EPOCH, Anonymous)
  private val merged = PullRequestMerged(id, proj, 3, Instant.EPOCH, Anonymous)

  private val state1 = PullRequestActive(id, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state2 = PullRequestActive(id, proj, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state3 = PullRequestClosed(id, proj, 3, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  private val tag = UserTag.unsafe("active")

  private lazy val eventLog = ScopedEventLog(
    eventStore,
    stateStore,
    PullRequest.stateMachine,
    (id: Label, c: PullRequestCommand) => AlreadyExists(id, c.project),
    Tagger[PullRequestEvent](
      {
        case t: PullRequestTagged => Some(tag -> t.targetRev)
        case _                    => None
      },
      {
        case _: PullRequestMerged => Some(tag)
        case _                    => None
      }
    ),
    maxDuration,
    xas
  )

  test("Evaluate successfully a command and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate(proj, id, Create(id, proj)).assert((opened, state1))
      _ <- eventStore.history(proj, id).assert(opened)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state1)
    } yield ()
  }

  test("Raise an error with a non-existent project") {
    eventLog.stateOr(ProjectRef.unsafe("xxx", "xxx"), id, NotFound).error(NotFound)
  }

  test("Raise an error with a non-existent id") {
    eventLog.stateOr(proj, Label.unsafe("xxx"), NotFound).error(NotFound)
  }

  test("Tag and check that the state has also been successfully tagged as well") {
    for {
      _ <- eventLog.evaluate(proj, id, Tag(id, proj, 2, 1)).assert((tagged, state2))
      _ <- eventStore.history(proj, id).assert(opened, tagged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state2)
      _ <- eventLog.stateOr(proj, id, tag, NotFound).assert(state1)
    } yield ()
  }

  test("Dry run successfully a command without persisting anything") {
    for {
      _ <- eventLog.dryRun(proj, id, Merge(id, proj, 3)).assert((merged, state3))
      _ <- eventStore.history(proj, id).assert(opened, tagged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state2)
    } yield ()
  }

  test("Evaluate successfully merge command and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate(proj, id, Merge(id, proj, 3)).assert((merged, state3))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state3)
    } yield ()
  }

  test("Check that the tagged state has been successfully removed after") {
    eventLog.stateOr(proj, id, tag, NotFound).error(NotFound)
  }

  test("Reject a command and persist nothing") {
    for {
      _ <- eventLog.evaluate(proj, id, Update(id, proj, 3)).error(PullRequestAlreadyClosed(id, proj))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state3)
    } yield ()
  }

  test("Raise an error and persist nothing") {
    val boom = Boom(id, proj, "fail")
    for {
      _ <- eventLog.evaluate(proj, id, boom).terminated(EvaluationFailure(boom, "RuntimeException", boom.message))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state3)
    } yield ()
  }

  test("Get a timeout and persist nothing") {
    val never = Never(id, proj)
    for {
      _ <- eventLog.evaluate(proj, id, never).terminated(EvaluationTimeout(never, maxDuration))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assert(state3)
    } yield ()
  }

  test("Get state at the specified revision") {
    eventLog.stateOr(proj, id, 1, NotFound, RevisionNotFound).assert(state1)
  }

  test("Raise an error with a non-existent id") {
    eventLog.stateOr(proj, Label.unsafe("xxx"), 1, NotFound, RevisionNotFound).error(NotFound)
  }

  test("Raise an error when providing a nonexistent revision") {
    eventLog.stateOr(proj, id, 10, NotFound, RevisionNotFound).error(RevisionNotFound(10, 3))
  }

}
