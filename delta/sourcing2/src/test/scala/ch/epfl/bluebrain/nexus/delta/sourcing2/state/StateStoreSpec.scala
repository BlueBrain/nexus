package ch.epfl.bluebrain.nexus.delta.sourcing2.state

import ch.epfl.bluebrain.nexus.delta.sourcing2.config.TrackQueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.{EntityId, EntityType, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{TrackConfig, TrackStore}
import ch.epfl.bluebrain.nexus.delta.sourcing2.{PostgresSetup, Transactors}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
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

  val person = EntityType("person")
  val group  = EntityType("group")
  val alice  = EntityId.unsafe("alice")
  val bob    = EntityId.unsafe("bob")
  val john   = EntityId.unsafe("john")

  val tracks      = trackStore.getOrCreate(Set("track1", "track2", "track3")).accepted
  val otherTracks = trackStore.getOrCreate(Set("track4")).accepted

  val version = "1.x"

  implicit val decoder: PayloadDecoder[Json] = PayloadDecoder.apply[Json](person)

  val aliceLatest = StateRow(
    Long.MinValue,
    person,
    alice,
    5,
    json"""{ "name": "Alice", "rev": 1 }""",
    tracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  val bobLatest = StateRow(
    Long.MinValue,
    person,
    bob,
    1,
    json"""{ "name": "Bob", "rev": 1 }""",
    tracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  val johnRow = StateRow(
    Long.MinValue,
    person,
    john,
    1,
    json"""{ "name": "John", "rev": 1 }""",
    otherTracks.values.toList,
    Tag.Latest,
    Instant.EPOCH,
    Instant.EPOCH,
    version
  )

  "Saving a row" should {

    "be ok" in {
      stateStore.save(aliceLatest).transact(xa).accepted

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
         """.stripMargin
        .query[(Long, EntityType, EntityId, Int, Json, List[Int], Tag, Instant, Instant, String)]
        .unique
        .transact(xa)
        .accepted shouldEqual StateRow.unapply(aliceLatest.copy(ordering = 1L)).value
    }

    "update the row on conflict and increment the ordering" in {
      val aliceNew = StateRow(
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

      stateStore.save(aliceNew).transact(xa).accepted

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
         """.stripMargin
        .query[(Long, EntityType, EntityId, Int, Json, List[Int], Tag, Instant, Instant, String)]
        .unique
        .transact(xa)
        .accepted shouldEqual StateRow.unapply(aliceNew.copy(ordering = 2L)).value
    }

  }

}
