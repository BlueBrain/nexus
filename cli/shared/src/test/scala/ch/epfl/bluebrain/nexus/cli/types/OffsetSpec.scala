package ch.epfl.bluebrain.nexus.cli.types

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.cli.types.Offset.{Sequence, TimeBasedUUID}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class OffsetSpec extends AnyWordSpecLike with Matchers {

  private val NUM_100NS_INTERVALS_SINCE_UUID_EPOCH = 0X01B21DD213814000L

  private def asInstant(timeBased: TimeBasedUUID): Instant =
    Instant.ofEpochMilli((timeBased.value.timestamp - NUM_100NS_INTERVALS_SINCE_UUID_EPOCH) / 10000)

  "Offsets" should {
    "be ordered based on UUID date" in {
      val time1 = TimeBasedUUID(UUID.fromString("49225740-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:32:36.148Z[UTC]
      val time2 = TimeBasedUUID(UUID.fromString("91be23d0-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:34:37.965Z[UTC]
      val time3 = TimeBasedUUID(UUID.fromString("91f95810-2019-11ea-a752-ffae2393b6e4")) // 2019-12-16T15:34:38.353Z[UTC]
      asInstant(time1).isBefore(asInstant(time2)) shouldEqual true
      asInstant(time2).isBefore(asInstant(time3)) shouldEqual true
      List[Offset](time2, time1, time3).sorted(Offset.offsetOrdering) shouldEqual List(time1, time2, time3)
    }

    "be ordered based on number " in {
      val time1 = Sequence(1L)
      val time2 = Sequence(2L)
      val time3 = Sequence(3L)
      List[Offset](time3, time1, time2).sorted(Offset.offsetOrdering) shouldEqual List(time1, time2, time3)
    }
  }

}
