package ch.epfl.bluebrain.nexus.sourcing

import ch.epfl.bluebrain.nexus.sourcing.TestCommand._
import ch.epfl.bluebrain.nexus.sourcing.TestEvent.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.sourcing.TestRejection.InvalidRevision
import ch.epfl.bluebrain.nexus.sourcing.TestState.{Current, Initial}
import monix.bio.IO

import scala.concurrent.duration.FiniteDuration

sealed trait TestCommand extends Product with Serializable {
  def rev: Int
}

object TestCommand {
  case class Increment(rev: Int, step: Int)                             extends TestCommand
  case class IncrementAsync(rev: Int, step: Int, sleep: FiniteDuration) extends TestCommand
  case class Initialize(rev: Int)                                       extends TestCommand
  case class Boom(rev: Int, message: String)                            extends TestCommand
  case class Never(rev: Int)                                            extends TestCommand
}

sealed trait TestEvent extends Product with Serializable {
  def rev: Int
}

object TestEvent {
  final case class Incremented(rev: Int, step: Int) extends TestEvent
  final case class Initialized(rev: Int)            extends TestEvent
}

sealed trait TestRejection extends Product with Serializable

object TestRejection {
  final case class InvalidRevision(rev: Int) extends TestRejection
}

sealed trait TestState extends Product with Serializable

object TestState {

  final case class Current(rev: Int, value: Int) extends TestState

  final case object Initial extends TestState
}

object EventSourceFixture {

  val initialState: TestState = TestState.Initial

  val next: (TestState, TestEvent) => TestState = {
    case (Initial, Incremented(1, step))             => TestState.Current(1, step)
    case (Initial, Initialized(rev))                 => TestState.Current(rev, 0)
    case (Current(_, value), Incremented(rev, step)) => TestState.Current(rev, value + step)
    case (Current(_, _), Initialized(rev))           => TestState.Current(rev, 0)
    case (other, _)                                  => other
  }

  def persistentDefinition: PersistentEventDefinition[TestState, TestCommand, TestEvent, TestRejection] =
    PersistentEventDefinition(
      "increment",
      TestState.Initial,
      next,
      evaluate,
      (_: TestEvent) => Set("increment")
    )

  def transientDefinition: TransientEventDefinition[TestState, TestCommand, TestEvent, TestRejection] =
    TransientEventDefinition(
      "increment",
      TestState.Initial,
      next,
      evaluate
    )

  def evaluate(state: TestState, cmd: TestCommand): IO[TestRejection, TestEvent] =
    (state, cmd) match {
      case (Current(revS, _), Boom(revC, message)) if revS == revC                  => IO.terminate(new RuntimeException(message))
      case (Initial, Boom(rev, message)) if rev == 0                                => IO.terminate(new RuntimeException(message))
      case (_, Boom(rev, _))                                                        => IO.raiseError(InvalidRevision(rev))
      case (Current(revS, _), Never(revC)) if revS == revC                          => IO.never
      case (Initial, Never(rev)) if rev == 0                                        => IO.never
      case (_, Never(rev))                                                          => IO.raiseError(InvalidRevision(rev))
      case (Initial, Increment(rev, step)) if rev == 0                              => IO.pure(Incremented(1, step))
      case (Initial, Increment(rev, _))                                             => IO.raiseError(InvalidRevision(rev))
      case (Initial, IncrementAsync(rev, step, duration)) if rev == 0               =>
        IO.sleep(duration) >> IO.pure(Incremented(1, step))
      case (Initial, IncrementAsync(rev, _, _))                                     => IO.raiseError(InvalidRevision(rev))
      case (Initial, Initialize(rev)) if rev == 0                                   => IO.pure(Initialized(1))
      case (Initial, Initialize(rev))                                               => IO.raiseError(InvalidRevision(rev))
      case (Current(revS, _), Increment(revC, step)) if revS == revC                => IO.pure(Incremented(revS + 1, step))
      case (Current(_, _), Increment(revC, _))                                      => IO.raiseError(InvalidRevision(revC))
      case (Current(revS, _), IncrementAsync(revC, step, duration)) if revS == revC =>
        IO.sleep(duration) >> IO.pure(Incremented(revS + 1, step))
      case (Current(_, _), IncrementAsync(revC, _, duration))                       =>
        IO.sleep(duration) >> IO.raiseError(InvalidRevision(revC))
      case (Current(revS, _), Initialize(revC)) if revS == revC                     => IO.pure(Initialized(revS + 1))
      case (Current(_, _), Initialize(rev))                                         => IO.raiseError(InvalidRevision(rev))
    }

}
