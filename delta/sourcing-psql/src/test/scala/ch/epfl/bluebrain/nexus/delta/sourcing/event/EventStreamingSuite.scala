package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestEvent.{PullRequestCreated, PullRequestUpdated}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.event.EventStreamingSuite.IdRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Anonymous
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing.{Arithmetic, MultiDecoder, Predicate, PullRequest}
import ch.epfl.bluebrain.nexus.testkit.{DoobieAssertions, DoobieFixture, MonixBioSuite}
import io.circe.Decoder
import doobie.implicits._

import java.time.Instant

class EventStreamingSuite extends MonixBioSuite with DoobieFixture with DoobieAssertions {

  override def munitFixtures: Seq[Fixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Stop)

  private lazy val arithmeticStore = GlobalEventStore[String, ArithmeticEvent](
    Arithmetic.entityType,
    ArithmeticEvent.serializer,
    queryConfig,
    xas
  )

  private lazy val prStore = ScopedEventStore[Label, PullRequestEvent](
    PullRequest.entityType,
    PullRequestEvent.serializer,
    queryConfig,
    xas
  )

  // Global events
  private val event1 = Plus("id1", 1, 12, Instant.EPOCH, Anonymous)
  private val event3 = Minus("id3", 1, 4, Instant.EPOCH, Anonymous)

  private val arithmeticDecoder: Decoder[IdRev] = ArithmeticEvent.serializer.codec.map { e => IdRev(e.id, e.rev) }

  // Scoped events
  private val id2 = Label.unsafe("id2")
  private val id4 = Label.unsafe("id4")
  private val id5 = Label.unsafe("id5")
  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj3")
  private val event2 = PullRequestCreated(id2, project1, Instant.EPOCH, Anonymous)
  private val event4 = PullRequestCreated(id4, project2, Instant.EPOCH, Anonymous)
  private val event5 = PullRequestUpdated(id2, project1, 2, Instant.EPOCH, Anonymous)
  private val event6 = PullRequestCreated(id5, project3, Instant.EPOCH, Anonymous)

  private val prDecoder: Decoder[IdRev] = PullRequestEvent.serializer.codec.map { e => IdRev(e.id.toString, e.rev) }

  implicit private val multiDecoder: MultiDecoder[IdRev] = MultiDecoder(Arithmetic.entityType -> arithmeticDecoder,  PullRequest.entityType -> prDecoder)

  test("Save events") {
    (arithmeticStore.save(event1) >>
      prStore.save(event2) >>
      arithmeticStore.save(event3) >>
      prStore.save(event4) >>
      prStore.save(event5) >>
      prStore.save(event6)
      ).transact(xas.write)
  }

  test("Get events of all types from the start") {
    EventStreaming.fetchAll(
      Predicate.root,
      List.empty,
      Offset.Start,
      queryConfig,
      xas
    ).map { e => e.offset -> e.value}.assert(
      Offset.at(1L) -> IdRev("id1", 1),
      Offset.at(2L) -> IdRev("id2", 1),
      Offset.at(3L) -> IdRev("id3", 1),
      Offset.at(4L) -> IdRev("id4", 1),
      Offset.at(5L) -> IdRev("id2", 2),
      Offset.at(6L) -> IdRev("id5", 1)
    )
  }

  test("Get events of all types from offset 2") {
    EventStreaming.fetchAll(
      Predicate.root,
      List.empty,
      Offset.at(2L),
      queryConfig,
      xas
    ).map { e => e.offset -> e.value}.assert(
      Offset.at(3L) -> IdRev("id3", 1),
      Offset.at(4L) -> IdRev("id4", 1),
      Offset.at(5L) -> IdRev("id2", 2),
      Offset.at(6L) -> IdRev("id5", 1)
    )
  }

  test("Get PR events from offset 2") {
    EventStreaming.fetchAll(
      Predicate.root,
      List(PullRequest.entityType),
      Offset.at(2L),
      queryConfig,
      xas
    ).map { e => e.offset -> e.value}.assert(
      Offset.at(4L) -> IdRev("id4", 1),
      Offset.at(5L) -> IdRev("id2", 2),
      Offset.at(6L) -> IdRev("id5", 1)
    )
  }

  test("Get events from project 1 from offset 1") {
    EventStreaming.fetchAll(
      Predicate.Project(project1),
      List.empty,
      Offset.at(1L),
      queryConfig,
      xas
    ).map { e => e.offset -> e.value}.assert(
      Offset.at(2L) -> IdRev("id2", 1),
      Offset.at(5L) -> IdRev("id2", 2)
    )
  }

  test("Get events from org 1 from offset 1") {
    EventStreaming.fetchAll(
      Predicate.Org(project1.organization),
      List.empty,
      Offset.at(1L),
      queryConfig,
      xas
    ).map { e => e.offset -> e.value}.assert(
      Offset.at(2L) -> IdRev("id2", 1),
      Offset.at(4L) -> IdRev("id4", 1),
      Offset.at(5L) -> IdRev("id2", 2)
    )
  }

}


object EventStreamingSuite {

  final case class IdRev(id: String, rev: Int)
}