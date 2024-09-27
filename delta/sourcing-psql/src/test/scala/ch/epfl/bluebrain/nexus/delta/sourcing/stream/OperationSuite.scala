package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.OperationSuite.{double, half, until}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.OperationInOutMatchErr
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.GenericPipe
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

import java.time.Instant

class OperationSuite extends NexusSuite {

  test("Run the double stream") {
    val sink       = CacheSink.states[Int]
    val operations = Operation.merge(double, sink).rightValue

    for {
      _ <- until(5).through(operations).rightValue.apply(Offset.Start).assertSize(5)
      _  = assert(sink.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink.successes.values.toList.sorted, List(0, 2, 4, 6, 8))
    } yield ()
  }

  test("Run the half stream") {
    val sink       = CacheSink.states[Double]
    val operations = Operation.merge(half, sink).rightValue

    for {
      _ <- until(5).through(operations).rightValue.apply(Offset.at(1L)).assertSize(4)
      _  = assert(sink.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink.successes.values.toList.sorted, List(0.5, 1.0, 1.5, 2.0))
    } yield ()
  }

  test("Fail as the input and output types don't match") {
    val sink = CacheSink.states[Int]
    Operation.merge(half, sink).assertLeft(OperationInOutMatchErr(half, sink))
  }

  test("Run the double stream as an tap operation") {
    // The values should be doubled in the first sink
    val sink1 = CacheSink.states[Int]
    // We should have the originals here
    val sink2 = CacheSink.states[Int]

    val tap = Operation.merge(double, sink1).rightValue.tap
    val all = Operation.merge(tap, sink2).rightValue

    for {
      _ <- until(5).through(all).rightValue.apply(Offset.Start).assertSize(5)
      _  = assert(sink1.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink1.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink1.successes.values.toList.sorted, List(0, 2, 4, 6, 8))
      _  = assert(sink2.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink2.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink2.successes.values.toList.sorted, List(0, 1, 2, 3, 4))
    } yield ()
  }
}

object OperationSuite {

  private val numberType = EntityType("number")

  private def until(n: Int): Source = Source(offset =>
    Stream.range(offset.value.toInt, n).covary[IO].map { i =>
      SuccessElem(numberType, nxv + i.toString, None, Instant.EPOCH, Offset.at(i.toLong), i, 1)
    }
  )

  val double: Pipe = new GenericPipe[Int, Int](Label.unsafe("double"), _.evalMap { v => IO.pure(v * 2) })
  val half: Pipe   = new GenericPipe[Int, Double](Label.unsafe("half"), _.evalMap { v => IO.pure((v.toDouble / 2)) })

}
