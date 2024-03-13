package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.Total
import ch.epfl.bluebrain.nexus.delta.sourcing.EvaluationError.InvalidState
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream

class StateMachineSuite extends NexusSuite {

  private val stm = Arithmetic.stateMachine

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
