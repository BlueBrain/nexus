package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreamingSuite.IdRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{Arithmetic, MultiDecoder, PullRequest, Scope}
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import doobie.implicits._
import io.circe.Decoder
import munit.AnyFixture

import java.time.Instant

class EventStreamingSuite extends BioSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Stop)

  private lazy val arithmeticStore = GlobalEventStore[Iri, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    queryConfig,
    xas
  )

  private lazy val prStore = ScopedEventStore[Iri, PullRequestEvent](
    PullRequest.entityType,
    PullRequestEvent.serializer,
    queryConfig,
    xas
  )

  private val id1 = nxv + "id1"
  private val id2 = nxv + "id2"
  private val id3 = nxv + "id3"
  private val id4 = nxv + "id4"
  private val id5 = nxv + "id5"

  // Global events
  private val event1 = Plus(id1, 1, 12, Instant.EPOCH, Anonymous)
  private val event3 = Minus(id3, 1, 4, Instant.EPOCH, Anonymous)

  private val arithmeticDecoder: Decoder[IdRev] = ArithmeticEvent.serializer.codec.map { e => IdRev(e.id, e.rev) }

  // Scoped events
  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")
  private val event2   = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)
  private val event4   = PullRequestCreated(id4, project2, Instant.EPOCH, Anonymous)
  private val event5   = PullRequestUpdated(id2, project1, 2, Instant.EPOCH, Anonymous)
  private val event6   = PullRequestCreated(id5, project3, Instant.EPOCH, Anonymous)

  private val prDecoder: Decoder[IdRev] = PullRequestEvent.serializer.codec.map { e => IdRev(e.id, e.rev) }

  implicit private val multiDecoder: MultiDecoder[IdRev] =
    MultiDecoder(Arithmetic.entityType -> arithmeticDecoder, PullRequest.entityType -> prDecoder)

  test("Save events") {
    (arithmeticStore.save(event1) >>
      prStore.unsafeSave(event2) >>
      arithmeticStore.save(event3) >>
      prStore.unsafeSave(event4) >>
      prStore.unsafeSave(event5) >>
      prStore.unsafeSave(event6)).transact(xas.write)
  }

  test("Get events of all types from the start") {
    EventStreaming
      .fetchAll(
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
        Offset.at(3L) -> IdRev(id3, 1),
        Offset.at(4L) -> IdRev(id4, 1),
        Offset.at(5L) -> IdRev(id2, 2),
        Offset.at(6L) -> IdRev(id5, 1)
      )
  }

  test("Get events of all types from offset 2") {
    EventStreaming
      .fetchAll(
        Scope.root,
        List.empty,
        Offset.at(2L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(3L) -> IdRev(id3, 1),
        Offset.at(4L) -> IdRev(id4, 1),
        Offset.at(5L) -> IdRev(id2, 2),
        Offset.at(6L) -> IdRev(id5, 1)
      )
  }

  test("Get PR events from offset 2") {
    EventStreaming
      .fetchAll(
        Scope.root,
        List(PullRequest.entityType),
        Offset.at(2L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(4L) -> IdRev(id4, 1),
        Offset.at(5L) -> IdRev(id2, 2),
        Offset.at(6L) -> IdRev(id5, 1)
      )
  }

  test("Get events from project 1 from offset 1") {
    EventStreaming
      .fetchAll(
        Scope.Project(project1),
        List.empty,
        Offset.at(1L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(2L) -> IdRev(id2, 1),
        Offset.at(5L) -> IdRev(id2, 2)
      )
  }

  test("Get events from org 1 from offset 1") {
    EventStreaming
      .fetchAll(
        Scope.Org(project1.organization),
        List.empty,
        Offset.at(1L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(2L) -> IdRev(id2, 1),
        Offset.at(4L) -> IdRev(id4, 1),
        Offset.at(5L) -> IdRev(id2, 2)
      )
  }

  test("Get all scoped events from offset 1") {
    EventStreaming
      .fetchScoped(
        Scope.Root,
        List.empty,
        Offset.at(1L),
        queryConfig,
        xas
      )
      .map { e => e.offset -> e.value }
      .assert(
        Offset.at(2L) -> IdRev(id2, 1),
        Offset.at(4L) -> IdRev(id4, 1),
        Offset.at(5L) -> IdRev(id2, 2),
        Offset.at(6L) -> IdRev(id5, 1)
      )
  }

}

object EventStreamingSuite {

  final case class IdRev(id: Iri, rev: Int)
}
