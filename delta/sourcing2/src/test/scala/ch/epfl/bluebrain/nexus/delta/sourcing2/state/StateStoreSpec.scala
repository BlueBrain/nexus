package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.TrackQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{Track, TrackConfig, TrackStore}
import ch.epfl.bluebrain.nexus.delta.sourcing2.{PostgresSetup, Transactors}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Json
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration._

class StateStoreSpec
    extends AnyWordSpec
    with PostgresSetup
    with CirceLiteral
    with EitherValuable
    with OptionValues
    with Matchers {

  private val shared           = Transactors.shared(xa)
  private val trackStore       = TrackStore(TrackConfig(500L, 30.minutes), shared)
  private val trackQueryConfig = TrackQueryConfig(2, 50.millis)

  private val stateStore = StateStore(trackStore, trackQueryConfig, shared)

  private val person = EntityType("person")
  private val alice  = EntityId.unsafe("alice")
  private val bob    = EntityId.unsafe("bob")
  private val john   = EntityId.unsafe("john")

  private val tracks      = trackStore.getOrCreate(Set("track1", "track2", "track3")).accepted
  private val otherTracks = trackStore.getOrCreate(Set("track4")).accepted

  private val version = "1.x"

  implicit val decoder: PayloadDecoder[Json] = PayloadDecoder.apply[Json](person)

  private val aliceLatest = StateRow(
    Long.MinValue,
    person,
    alice,
    5,
    json"""{ "name": "Alice", "rev": 5 }""",
    tracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private val aliceNew = StateRow(
    Long.MinValue,
    person,
    alice,
    6,
    json"""{ "name": "Alice New", "rev": 6 }""",
    16 :: tracks.values.toList,
    Tag.Latest,
    Instant.EPOCH.plusMillis(100L),
    Instant.EPOCH.plusMillis(100L),
    "2.x"
  )

  private val aliceTagged = StateRow(
    Long.MinValue,
    person,
    alice,
    3,
    json"""{ "name": "Alice Tagged", "rev": 3 }""",
    tracks.values.toList,
    Tag.UserTag("tag3"),
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private val bobLatest = StateRow(
    Long.MinValue,
    person,
    bob,
    12,
    json"""{ "name": "Bob", "rev": 12 }""",
    tracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private val johnLatest = StateRow(
    Long.MinValue,
    person,
    john,
    7,
    json"""{ "name": "John", "rev": 7 }""",
    otherTracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  private def select(row: StateRow) =
    sql"""
           | SELECT
           |  ordering,
           |  entity_type,
           |  entity_id,
           |  revision,
           |  payload,
           |  tracks,
           |  tag,
           |  updated_at,
           |  written_at,
           |  write_version
           | FROM states
           | WHERE entity_type = ${row.tpe}
           | AND entity_id = ${row.id}
           | AND tag = ${row.tag}
           """.stripMargin
      .query[(Long, EntityType, EntityId, Int, Json, List[Int], Tag, Instant, Instant, String)]
      .unique
      .transact(xa)

  "Saving a row" should {

    "be ok for a latest state" in {
      stateStore.save(aliceLatest).transact(xa).accepted

      select(aliceLatest).accepted shouldEqual StateRow.unapply(aliceLatest.copy(ordering = 1L)).value
    }

    "update the row on conflict and increment the ordering" in {
      stateStore.save(aliceNew).transact(xa).accepted

      select(aliceNew).accepted shouldEqual StateRow.unapply(aliceNew.copy(ordering = 2L)).value
    }

    "be ok for a tagged state" in {
      stateStore.save(aliceTagged).transact(xa).accepted

      select(aliceTagged).accepted shouldEqual StateRow.unapply(aliceTagged.copy(ordering = 3L)).value
    }
  }

  "Getting the latest state" should {
    "work for an existing id" in {
      stateStore.latestState(aliceNew.tpe, aliceNew.id).accepted.value shouldEqual aliceNew.payload
    }

    "return None for a unknown id" in {
      stateStore.latestState(aliceLatest.tpe, EntityId.unsafe("xxx")).accepted shouldEqual None
    }
  }

  "Getting the tagged state" should {
    "work for an existing id and tag" in {
      stateStore.tagged(aliceTagged.tpe, aliceTagged.id, aliceTagged.tag).accepted.value shouldEqual aliceTagged.payload
    }

    "return None for a unknown id" in {
      stateStore.tagged(aliceLatest.tpe, EntityId.unsafe("xxx"), aliceTagged.tag).accepted shouldEqual None
    }

    "return None for a unknown tag" in {
      stateStore.tagged(aliceLatest.tpe, aliceTagged.id, Tag.UserTag("xxx")).accepted shouldEqual None
    }
  }

  "Deleting a tagged state" should {

    "be ok" in {
      val bobTagged = bobLatest.copy(tag = Tag.UserTag("tag1"))
      stateStore.save(bobTagged).transact(xa).accepted

      stateStore.tagged(bobTagged.tpe, bobTagged.id, bobTagged.tag).accepted.value shouldEqual bobTagged.payload

      stateStore.deleteTagged(bobTagged.tpe, bobTagged.id, Tag.UserTag("tag1")).transact(xa).accepted shouldEqual true

      stateStore.tagged(bobTagged.tpe, bobTagged.id, bobTagged.tag).accepted shouldEqual None
    }

  }

  "Inserting more states" should {
    "work" in {
      (
        stateStore.save(bobLatest) >>
          stateStore.save(johnLatest) >>
          stateStore.save(bobLatest.copy(revision = 2, tag = Tag.UserTag("tag1"))) >>
          stateStore.save(bobLatest.copy(revision = 1, tag = Tag.UserTag("tag3")))
      ).transact(xa).accepted

      sql"SELECT count(*) FROM states".query[Long].unique.transact(xa).accepted shouldEqual 6L
    }
  }

  "Streaming current states by track" should {
    "get the latest states for the given track from the start" in {
      stateStore
        .currentStatesByTrack[Json](
          Track.Single("track1"),
          Tag.Latest,
          Offset.Start
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, alice, 6),
        (person, bob, 12)
      )
    }

    "get the latest states for the given track from the given offset" in {
      stateStore
        .currentStatesByTrack[Json](
          Track.Single("track1"),
          Tag.Latest,
          Offset.At(2)
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, bob, 12)
      )
    }

    "get the latest states for all tracks from the given offset" in {
      stateStore
        .currentStatesByTrack[Json](
          Track.All,
          Tag.Latest,
          Offset.At(2)
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, bob, 12),
        (person, john, 7)
      )
    }

    "get the tagged states for the given track/tag/offset" in {
      stateStore
        .currentStatesByTrack[Json](
          Track.All,
          Tag.UserTag("tag3"),
          Offset.At(2)
        )
        .compile
        .toList
        .accepted
        .map { e =>
          (e.tpe, e.id, e.rev)
        } shouldEqual List(
        (person, alice, 3),
        (person, bob, 1)
      )
    }

    "get an empty stream for an unknown track" in {
      stateStore
        .currentStatesByTrack(
          Track.Single("xxx"),
          Tag.Latest,
          Offset.Start
        )
        .compile
        .toList
        .accepted shouldEqual List.empty
    }

  }

  "Streaming states by track" should {

    "get the states for the given track from the start" in {
      stateStore
        .statesByTrack(
          Track.Single("track1"),
          Tag.Latest,
          Offset.Start
        )
        .map(Some(_))
        .timeout(500.millis)
        .handleErrorWith(_ => Stream.emit(None))
        .compile
        .toList
        .accepted
        .map {
          _.map { e =>
            (e.tpe, e.id, e.rev)
          }
        } shouldEqual List(
        Some((person, alice, 6)),
        Some((person, bob, 12)),
        None
      )
    }

  }

}
