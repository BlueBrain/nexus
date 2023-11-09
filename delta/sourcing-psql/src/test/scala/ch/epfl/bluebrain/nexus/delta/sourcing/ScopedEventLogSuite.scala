package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationFailure, EvaluationTimeout}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestCommand._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestMerged, PullRequestTagged}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestRejection._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.{PullRequestActive, PullRequestClosed}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.{PullRequestCommand, PullRequestEvent, PullRequestState}
import ch.epfl.bluebrain.nexus.delta.sourcing.ScopedEntityDefinition.Tagger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.ScopedEventStore
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.DependsOn
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model._
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateStore
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Decoder
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration._

class ScopedEventLogSuite extends CatsEffectSuite with Doobie.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

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

  private val id   = nxv + "id"
  private val id2  = nxv + "id2"
  private val proj = ProjectRef.unsafe("org", "proj")

  private val opened = PullRequestCreated(id, proj, Instant.EPOCH, Anonymous)
  private val tagged = PullRequestTagged(id, proj, 2, 1, Instant.EPOCH, Anonymous)
  private val merged = PullRequestMerged(id, proj, 3, Instant.EPOCH, Anonymous)

  private val state1 = PullRequestActive(id, proj, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state2 = PullRequestActive(id, proj, 2, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)
  private val state3 = PullRequestClosed(id, proj, 3, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  private val tag = UserTag.unsafe("active")

  private lazy val eventLog: ScopedEventLog[
    Iri,
    PullRequestState,
    PullRequestCommand,
    PullRequestEvent,
    PullRequest.PullRequestRejection
  ] = ScopedEventLog(
    PullRequest.entityType,
    eventStore,
    stateStore,
    PullRequest.stateMachine,
    (id: Iri, c: PullRequestCommand) => AlreadyExists(id, c.project),
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
    {
      case s if s.id == id => Some(Set(DependsOn(s.project, id2)))
      case _               => None
    },
    maxDuration,
    xas
  )

  test("Evaluate successfully a command and store both event and state for an initial state") {
    implicit val decoder: Decoder[PullRequestState] = PullRequestState.serializer.codec
    val expectedDependencies                        = Set(DependsOn(proj, id2))
    for {
      _        <- eventLog.evaluate(proj, id, Create(id, proj)).assertEquals((opened, state1))
      _        <- eventStore.history(proj, id).assert(opened)
      _        <- eventLog.stateOr(proj, id, NotFound).assertEquals(state1)
      // Check dependency on id2
      _        <- EntityDependencyStore.directDependencies(proj, id, xas).assertEquals(expectedDependencies)
      _        <- EntityDependencyStore.recursiveDependencies(proj, id, xas).assertEquals(expectedDependencies)
      _        <- EntityDependencyStore.decodeDirectDependencies(proj, id, xas).assertEquals(List.empty)
      // Create state for id2
      state1Id2 = state1.copy(id = id2)
      _        <- eventLog.evaluate(proj, id2, Create(id2, proj)).map(_._2).assertEquals(state1Id2)
      _        <- eventLog.stateOr(proj, id2, NotFound).assertEquals(state1Id2)
      _        <- EntityDependencyStore.decodeDirectDependencies(proj, id, xas).assertEquals(List(state1Id2))
    } yield ()
  }

  test("Raise an error with a non-existent project") {
    eventLog.stateOr(ProjectRef.unsafe("xxx", "xxx"), id, NotFound).intercept(NotFound)
  }

  test("Raise an error with a non-existent id") {
    eventLog.stateOr(proj, nxv + "xxx", NotFound).intercept(NotFound)
  }

  test("Tag and check that the state has also been successfully tagged as well") {
    for {
      _ <- eventLog.evaluate(proj, id, TagPR(id, proj, 2, 1)).assertEquals((tagged, state2))
      _ <- eventStore.history(proj, id).assert(opened, tagged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state2)
      _ <- eventLog.stateOr(proj, id, tag, NotFound, TagNotFound).assertEquals(state1)
    } yield ()
  }

  test("Dry run successfully a command without persisting anything") {
    for {
      _ <- eventLog.dryRun(proj, id, Merge(id, proj, 3)).assertEquals((merged, state3))
      _ <- eventStore.history(proj, id).assert(opened, tagged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state2)
    } yield ()
  }

  test("Evaluate successfully merge command and store both event and state for an initial state") {
    for {
      _ <- eventLog.evaluate(proj, id, Merge(id, proj, 3)).assertEquals((merged, state3))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state3)
    } yield ()
  }

  test("Check that the tagged state has been successfully removed after") {
    val query = sql"""SELECT type, org, project, id, tag, instant FROM scoped_tombstones"""
      .query[(EntityType, Label, Label, Iri, Tag, Instant)]
      .unique
      .transact(xas.read)
    for {
      _ <- eventLog.stateOr(proj, id, tag, NotFound, TagNotFound).intercept(TagNotFound)
      _ <- query.assertEquals((PullRequest.entityType, proj.organization, proj.project, id, tag, Instant.EPOCH))
    } yield ()

  }

  test("Reject a command and persist nothing") {
    for {
      _ <- eventLog.evaluate(proj, id, Update(id, proj, 3)).intercept(PullRequestAlreadyClosed(id, proj))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state3)
    } yield ()
  }

  test("Raise an error and persist nothing") {
    val boom = Boom(id, proj, "fail")
    for {
      _ <- eventLog.evaluate(proj, id, boom).intercept(EvaluationFailure(boom, "RuntimeException", boom.message))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state3)
    } yield ()
  }

  test("Get a timeout and persist nothing") {
    val never = Never(id, proj)
    for {
      _ <- eventLog.evaluate(proj, id, never).intercept(EvaluationTimeout(never, maxDuration))
      _ <- eventStore.history(proj, id).assert(opened, tagged, merged)
      _ <- eventLog.stateOr(proj, id, NotFound).assertEquals(state3)
    } yield ()
  }

  test("Get state at the specified revision") {
    eventLog.stateOr(proj, id, 1, NotFound, RevisionNotFound).assertEquals(state1)
  }

  test("Raise an error with a non-existent id") {
    eventLog.stateOr(proj, nxv + "xxx", 1, NotFound, RevisionNotFound).intercept(NotFound)
  }

  test("Raise an error when providing a nonexistent revision") {
    eventLog.stateOr(proj, id, 10, NotFound, RevisionNotFound).intercept(RevisionNotFound(10, 3))
  }

  test("Stream continuously the current states") {
    eventLog
      .states(Scope.root, Offset.Start)
      .assertSize(2)
  }

}
