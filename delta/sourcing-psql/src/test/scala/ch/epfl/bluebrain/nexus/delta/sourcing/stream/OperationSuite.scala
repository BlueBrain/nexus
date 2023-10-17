package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.PipeCatsEffect
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.OperationSuite.{double, half, until}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{LeapingNotAllowedErr, OperationInOutMatchErr}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.GenericPipeCats
import ch.epfl.bluebrain.nexus.testkit.bio.StreamAssertions
import ch.epfl.bluebrain.nexus.testkit.ce.{CatsEffectSuite, CatsIOValues}
import fs2.Stream
import shapeless.Typeable

import java.time.Instant

class OperationSuite extends CatsEffectSuite with StreamAssertions {

  test("Run the double stream") {
    val sink       = CacheSink.states[Int]
    val operations = OperationF.merge[IO](double, sink).rightValue

    for {
      _ <- until(5).through(operations).rightValue.apply(Offset.Start).assertSize(5)
      _  = assert(sink.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink.successes.values.toList.sorted, List(0, 2, 4, 6, 8))
    } yield ()
  }

  test("Run the half stream") {
    val sink       = CacheSink.states[Double]
    val operations = OperationF.merge[IO](half, sink).rightValue

    for {
      _ <- until(5).through(operations).rightValue.apply(Offset.at(1L)).assertSize(4)
      _  = assert(sink.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink.successes.values.toList.sorted, List(0.5, 1.0, 1.5, 2.0))
    } yield ()
  }

  test("Fail as the input and output types don't match") {
    val sink = CacheSink.states[Int]
    OperationF.merge(half, sink).assertLeft(OperationInOutMatchErr(half, sink))
  }

  test("Run the double stream as an tap operation") {
    // The values should be doubled in the first sink
    val sink1 = CacheSink.states[Int]
    // We should have the originals here
    val sink2 = CacheSink.states[Int]

    val tap = OperationF.merge(double, sink1).rightValue.tap
    val all = OperationF.merge(tap, sink2).rightValue

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

  test("Run the double stream with a leaped part") {
    val sink = CacheSink.states[Int]

    val first  = double.identityLeap(Offset.at(2L)).rightValue
    val second = OperationF.merge(double, sink).rightValue
    val all    = OperationF.merge(first, second).rightValue

    for {
      _ <- until(5).through(all).rightValue.apply(Offset.Start).assertSize(5)
      _  = assert(sink.failed.isEmpty, "No failure should be detected.")
      _  = assert(sink.dropped.isEmpty, "No dropped should be detected.")
      _  = assertEquals(sink.successes.values.toList.sorted, List(0, 2, 4, 12, 16))
    } yield ()
  }

  test("Leaping is not possible for an operation where in and out are not aligned") {
    half.identityLeap(Offset.at(2L)).assertLeft(LeapingNotAllowedErr(half, Typeable[Int]))
  }

}

object OperationSuite extends CatsIOValues {

  private val numberType = EntityType("number")

  private def until(n: Int): SourceCatsEffect = SourceF(offset =>
    Stream.range(offset.value.toInt, n).covary[IO].map { i =>
      SuccessElem(numberType, nxv + i.toString, None, Instant.EPOCH, Offset.at(i.toLong), i, 1)
    }
  )

  val double: PipeCatsEffect = new GenericPipeCats[Int, Int](Label.unsafe("double"), x => IO.pure(x.map { v => v * 2 }))
  val half: PipeCatsEffect   =
    new GenericPipeCats[Int, Double](Label.unsafe("half"), x => IO.pure(x.map { v => v.toDouble / 2 }))

}
