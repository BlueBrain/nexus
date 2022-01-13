package ch.epfl.bluebrain.nexus.delta.sourcing2.track

import ch.epfl.bluebrain.nexus.delta.sourcing2.{PostgresSetup, Transactors}
import doobie.implicits._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

class TrackStoreSpec extends AnyWordSpec with PostgresSetup with OptionValues with Matchers {

  private val config = TrackConfig(500L, 30.minutes)

  private val trackStore =
    TrackStore(config, Transactors.shared(xa))

  private def sequenceValue = sql"select last_value from tracks_id_seq".query[Long].unique.transact(xa)

  "Get or create tags" should {

    "create then fetch the 3 different tags" in {
      trackStore.getOrCreate(Set("track1", "track2", "track3")).accepted.keySet shouldEqual Set(
        "track1",
        "track2",
        "track3"
      )
      val seqValue = sequenceValue.accepted
      trackStore.getOrCreate(Set("track1", "track2", "track3")).accepted.keySet shouldEqual Set(
        "track1",
        "track2",
        "track3"
      )
      // There is no new tag so the sequence should not have been incremented
      sequenceValue.accepted shouldEqual seqValue
    }

    "create 1 new tag and fetch 1 existing one" in {
      trackStore.getOrCreate(Set("track3", "track4")).accepted.keySet shouldEqual Set("track3", "track4")
    }
  }

  "Selecting a track" should {

    "get all tracks when all is provided" in {
      trackStore.select(Track.All).accepted shouldEqual SelectedTrack.All
    }

    "get the id for the single track" in {
      val tag1Id = trackStore.fetch("track1").accepted.value
      trackStore.select(Track.Single("track1")).accepted shouldEqual SelectedTrack.Single(tag1Id)
    }

    "get no found for an unknown track" in {
      trackStore.select(Track.Single("xxx")).accepted shouldEqual SelectedTrack.NotFound
    }

  }

}
