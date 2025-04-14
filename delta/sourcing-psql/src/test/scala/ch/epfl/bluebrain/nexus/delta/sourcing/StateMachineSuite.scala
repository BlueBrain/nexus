package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticCommand.*
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticRejection.NegativeTotal
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.Total
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.{EvaluationTimeout, InvalidState}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

import scala.concurrent.duration.*

class StateMachineSuite extends NexusSuite {

  private val stm = Arithmetic.stateMachine

  private val current = Total(1, 4)

  private val maxDuration = 100.millis

  List(
    (None, Add(5))               -> (Plus(1, 5), Total(1, 5)),
    (Some(current), Add(5))      -> (Plus(2, 5) -> Total(2, 9)),
    (Some(current), Subtract(2)) -> (Minus(2, 2), Total(2, 2))
  ).foreach { case ((original, command), (event, newState)) =>
    test(s"Evaluate successfully state ${original.map(s => s"rev:${s.rev}, value:${s.value}")} with command $command") {
      stm.evaluate(original, command, maxDuration).assertEquals((event, newState))
    }
  }

  List(
    (None, Subtract(2))          -> NegativeTotal(-2),
    (Some(current), Subtract(5)) -> NegativeTotal(-1)
  ).foreach { case ((original, command), rejection) =>
    test(s"Evaluate and reject state ${original.map(s => s"rev:${s.rev}, value:${s.value}")} with command $command") {
      stm.evaluate(original, command, maxDuration).interceptEquals(rejection)
    }
  }

  test("Evaluate and get an RuntimeException with the expected message") {
    stm.evaluate(None, Boom("Game over"), maxDuration).interceptMessage[RuntimeException]("Game over")
  }

  test("Evaluate and get a timeout error") {
    stm.evaluate(None, Never, maxDuration).interceptEquals(EvaluationTimeout(Never, maxDuration))
  }

  test("Compute state and get back the initial state from an empty stream of events") {
    stm.computeState(Stream.empty).assertEquals(None)
  }

  test("Compute state from a stream of events") {
    stm
      .computeState(Stream(Plus(1, 2), Plus(2, 8), Minus(3, 6)))
      .assertEquals(Some(Total(3, 4)))
  }

  test("Get an error from an invalid stream of events") {
    stm
      .computeState(Stream(Plus(1, 2), Minus(2, 6)))
      .interceptEquals(InvalidState(Some(Total(1, 2)), Minus(2, 6)))
  }

}
