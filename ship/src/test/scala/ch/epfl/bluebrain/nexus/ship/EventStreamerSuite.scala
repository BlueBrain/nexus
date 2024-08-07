package ch.epfl.bluebrain.nexus.ship

import cats.kernel.Order
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream
import munit.Location

class EventStreamerSuite extends NexusSuite {

  private def readFrom(fromOffset: Offset)(expectedMin: Offset, expectedMax: Offset)(implicit location: Location) = {
    Stream
      .eval(loader.absoluteFs2Path("import/multi-part-import"))
      .flatMap { path =>
        EventStreamer.localStreamer.stream(path, fromOffset)
      }
      .scan((Offset.at(Long.MaxValue), Offset.at(0L))) { case ((min, max), event) =>
        (Order.min(min, event.ordering), Order.max(max, event.ordering))
      }
      .compile
      .lastOrError
      .assertEquals((expectedMin, expectedMax))
  }

  test("Streaming from directory and keep the expected min / max from the beginning") {
    readFrom(Offset.start)(Offset.at(2163821L), Offset.at(9999999L))
  }

  test("Streaming from directory and keep the expected min / max from offset 2500000L") {
    readFrom(Offset.at(2500000L))(Offset.at(4879496L), Offset.at(9999999L))
  }
}
