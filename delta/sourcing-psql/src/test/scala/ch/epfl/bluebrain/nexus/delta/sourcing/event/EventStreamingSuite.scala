package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreamingSuite.IdRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, PullRequest, Scope}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import io.circe.Decoder
import munit.AnyFixture

import java.time.Instant

class EventStreamingSuite extends NexusSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Stop)


  private lazy val gitlabPrStore = ScopedEventStore[Iri, PullRequestEvent](
    PullRequest.entityType,
    PullRequestEvent.serializer,
    queryConfig,
    xas
  )

  private lazy val githubPrStore = ScopedEventStore[Iri, PullRequestEvent](
    EntityType("github"),
    PullRequestEvent.serializer,
    queryConfig,
    xas
  )

  private val id1 = nxv + "id1"
  private val id2 = nxv + "id2"
  private val id3 = nxv + "id3"
  private val id4 = nxv + "id4"

  // Scoped events
  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")
  private val event1   = PullRequestCreated(id1, project1, Instant.EPOCH, Anonymous)
  private val event2   = PullRequestCreated(id2, project2, Instant.EPOCH, Anonymous)
  private val event3   = PullRequestUpdated(id1, project1, 2, Instant.EPOCH, Anonymous)
  private val event4   = PullRequestCreated(id3, project3, Instant.EPOCH, Anonymous)
  private val event5   = PullRequestCreated(id4, project3, Instant.EPOCH, Anonymous)

  private val prDecoder: Decoder[IdRev] = PullRequestEvent.serializer.codec.map { e => IdRev(e.id, e.rev) }

  implicit private val multiDecoder: MultiDecoder[IdRev] =
    MultiDecoder(PullRequest.entityType -> prDecoder, EntityType("github") -> prDecoder)

  test("Save events") {
    (gitlabPrStore.unsafeSave(event1) >>
      gitlabPrStore.unsafeSave(event2) >>
      gitlabPrStore.unsafeSave(event3) >>
      gitlabPrStore.unsafeSave(event4) >>
      githubPrStore.unsafeSave(event5)).transact(xas.write)
  }

  test("Get events of all types from the start") {
    EventStreaming
      .fetchScoped(
        Scope.root,
        List.empty,
        Offset.Start,
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(1L) -> IdRev(id1, 1),
        Offset.at(2L) -> IdRev(id2, 1),
        Offset.at(3L) -> IdRev(id1, 2),
        Offset.at(4L) -> IdRev(id3, 1),
        Offset.at(5L) -> IdRev(id4, 1)
      )
  }

  test("Get events of all types from offset 2") {
    EventStreaming
      .fetchScoped(
        Scope.root,
        List.empty,
        Offset.at(2L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(3L) -> IdRev(id1, 2),
        Offset.at(4L) -> IdRev(id3, 1),
        Offset.at(5L) -> IdRev(id4, 1)
      )
  }

  test("No events for unknown type") {
    EventStreaming
      .fetchScoped(
        Scope.root,
        List(EntityType("RandomType")),
        Offset.start,
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assertEmpty
  }

  test("Events for github entity type") {
    EventStreaming
      .fetchScoped(
        Scope.root,
        List(EntityType("github")),
        Offset.start,
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(5L) -> IdRev(id4, 1)
      )
  }

  test("Get events from project 1 from offset 1") {
    EventStreaming
      .fetchScoped(
        Scope.Project(project1),
        List.empty,
        Offset.at(1L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(3L) -> IdRev(id1, 2)
      )
  }

  test("Get events from org 1 from offset 1") {
    EventStreaming
      .fetchScoped(
        Scope.Org(project1.organization),
        List.empty,
        Offset.at(1L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(2L) -> IdRev(id2, 1),
        Offset.at(3L) -> IdRev(id1, 2)
      )
  }

}

object EventStreamingSuite {

  final case class IdRev(id: Iri, rev: Int)
}
