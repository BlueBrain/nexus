package ch.epfl.bluebrain.nexus.delta.sourcing2.event

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.TrackQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityScope, EntityType}
import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{Track, TrackConfig, TrackStore}
import ch.epfl.bluebrain.nexus.delta.sourcing2.{PostgresSetup, Transactors}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.postgres.sqlstate
import io.circe.Json
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration._

class EventStoreSpec
    extends AnyWordSpec
    with PostgresSetup
    with CirceLiteral
    with EitherValuable
    with OptionValues
    with Matchers {

  private val shared           = Transactors.shared(xa)
  private val trackStore       = TrackStore(TrackConfig(500L, 30.minutes), shared)
  private val trackQueryConfig = TrackQueryConfig(2, 50.millis)

  private val eventStore = EventStore(trackStore, trackQueryConfig, shared)

  private val person = EntityType("person")
  private val group  = EntityType("group")
  private val alice  = EntityId.unsafe("alice")
  private val bob    = EntityId.unsafe("bob")
  private val john   = EntityId.unsafe("john")

  private val ch  = EntityScope("CH")
  private val ita = EntityScope("IT")

  private val tracks      = trackStore.getOrCreate(Set("track1", "track2", "track3")).accepted
  private val otherTracks = trackStore.getOrCreate(Set("track4")).accepted

  private val version = "1.x"

  implicit private val decoder: PayloadDecoder[Json] = PayloadDecoder.apply[Json](person)

  private val aliceRow = EventRow(
    Long.MinValue,
    person,
    alice,
    1,
    ch,
    json"""{ "name": "Alice", "rev": 1 }""",
    tracks.values.toList,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private val bobRow = EventRow(
    Long.MinValue,
    person,
    bob,
    1,
    ch,
    json"""{ "name": "Bob", "rev": 1 }""",
    tracks.values.toList,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private val johnRow = EventRow(
    Long.MinValue,
    person,
    john,
    1,
    ch,
    json"""{ "name": "John", "rev": 1 }""",
    otherTracks.values.toList,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  "Saving a row" should {

    "be ok and then fail if we attempt again" in {
      eventStore.save(aliceRow).transact(xa).accepted

      sql"""
           | SELECT
           |  ordering,
           |  entity_type,
           |  entity_id,
           |  revision,
           |  scope,
           |  payload,
           |  tracks,
           |  instant,
           |  written_at,
           |  write_version
           | FROM events
         """.stripMargin
        .query[(Long, EntityType, EntityId, Int, String, Json, List[Int], Instant, Instant, String)]
        .unique
        .transact(xa)
        .accepted shouldEqual (
        (
          1L,
          aliceRow.tpe,
          aliceRow.id,
          aliceRow.revision,
          aliceRow.scope.value,
          aliceRow.payload,
          aliceRow.tracks,
          aliceRow.instant,
          aliceRow.writtenAt,
          aliceRow.writeVersion
        )
      )
    }

    "fail if we try to insert another event with the same type/id/rev" in {
      eventStore
        .save(aliceRow.copy(scope = ita))
        .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
          "Fail!"
        }
        .transact(xa)
        .accepted
        .leftValue
    }

    "fail if we try to save an event with an id already used in the same scope" in {
      eventStore
        .save(aliceRow.copy(tpe = group))
        .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION =>
          "Fail!"
        }
        .transact(xa)
        .accepted
        .leftValue
    }

    "insert some more events for more entities / revisions" in {
      (
        eventStore.save(bobRow) >>
          eventStore.save(johnRow) >>
          eventStore.save(aliceRow.copy(revision = 2, payload = json"""{ "name": "Alice", "rev": 2 }""")) >>
          eventStore.save(aliceRow.copy(revision = 3, payload = json"""{ "name": "Alice", "rev": 3 }"""))
      ).transact(xa).accepted

      sql"SELECT count(*) FROM events".query[Long].unique.transact(xa)
    }
  }

  "Streaming by id" should {

    "work without upper bound" in {
      eventStore.currentEventsById[Json](person, alice).compile.toList.accepted shouldEqual List(
        aliceRow.payload,
        json"""{ "name": "Alice", "rev": 2 }""",
        json"""{ "name": "Alice", "rev": 3 }"""
      )
    }

    "work with an upper bound" in {
      eventStore.currentEventsById[Json](person, alice).compile.toList.accepted shouldEqual List(
        aliceRow.payload,
        json"""{ "name": "Alice", "rev": 2 }""",
        json"""{ "name": "Alice", "rev": 3 }"""
      )
    }
  }

  "Streaming current events by track" should {

    "get the events for the given track from the start" in {
      eventStore
        .currentEventsByTrack[Json](
          Track.Single("track1"),
          Offset.Start
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, alice, 1),
        (person, bob, 1),
        (person, alice, 2),
        (person, alice, 3)
      )
    }

    "get the events for the given track from the given offset" in {
      eventStore
        .currentEventsByTrack[Json](
          Track.Single("track1"),
          Offset.At(1)
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, bob, 1),
        (person, alice, 2),
        (person, alice, 3)
      )
    }

    "get the events for all tracks from the start" in {
      eventStore
        .currentEventsByTrack[Json](
          Track.All,
          Offset.Start
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, alice, 1),
        (person, bob, 1),
        (person, john, 1),
        (person, alice, 2),
        (person, alice, 3)
      )
    }

    "get the events for all tracks from the given offset" in {
      eventStore
        .currentEventsByTrack[Json](
          Track.All,
          Offset.At(1)
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, bob, 1),
        (person, john, 1),
        (person, alice, 2),
        (person, alice, 3)
      )
    }

    "get an empty stream for an unknown track" in {
      eventStore
        .eventsByTrack(
          Track.Single("xxx"),
          Offset.Start
        )
        .compile
        .toList
        .accepted shouldEqual List.empty
    }

  }

  "Streaming events by track" should {

    "get the events for the given track from the start" in {
      eventStore
        .eventsByTrack[Json](
          Track.Single("track1"),
          Offset.Start
        )
        .timeout(500.millis)
        .mask
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, alice, 1),
        (person, bob, 1),
        (person, alice, 2),
        (person, alice, 3)
      )
    }
  }

}
