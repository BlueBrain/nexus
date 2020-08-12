package ch.epfl.bluebrain.nexus.sourcingnew

import cats.effect.{Async, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.Command._
import ch.epfl.bluebrain.nexus.sourcingnew.Event.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.sourcingnew.Rejection.InvalidRevision
import ch.epfl.bluebrain.nexus.sourcingnew.State.{Current, Initial}

import scala.concurrent.duration.FiniteDuration

sealed trait Command extends Product with Serializable {
  def rev: Int
}

object Command {
  case class Increment(rev: Int, step: Int)                             extends Command
  case class IncrementAsync(rev: Int, step: Int, sleep: FiniteDuration) extends Command
  case class Initialize(rev: Int)                                       extends Command
  case class Boom(rev: Int, message: String)                            extends Command
  case class Never(rev: Int)                                            extends Command
}

sealed trait Event extends Product with Serializable {
  def rev: Int
}

object Event {
  final case class Incremented(rev: Int, step: Int) extends Event
  final case class Initialized(rev: Int)            extends Event
}

sealed trait Rejection extends Product with Serializable

object Rejection {
  final case class InvalidRevision(rev: Int) extends Rejection
}

sealed trait State extends Product with Serializable

object State {

  final case class Current(rev: Int, value: Int) extends State

  final case object Initial                      extends State
}

object EventSourceFixture {

  val initialState: State = State.Initial

  val next: (State, Event) => State = {
    case (Initial, Incremented(1, step))             => State.Current(1, step)
    case (Initial, Initialized(rev))                 => State.Current(rev, 0)
    case (Current(_, value), Incremented(rev, step)) => State.Current(rev, value + step)
    case (Current(_, _), Initialized(rev))           => State.Current(rev, 0)
    case (other, _)                                  => other
  }

  def persistentDefinition[F[_]: Async: Timer]: PersistentEventDefinition[F, State, Command, Event, Rejection] = PersistentEventDefinition(
    "increment",
    State.Initial,
    next,
    evaluate[F],
    (_: Event) => Set("increment")
  )

  def transientDefinition[F[_]: Async: Timer]: TransientEventDefinition[F, State, Command, Event, Rejection] = TransientEventDefinition(
    "increment",
    State.Initial,
    next,
    evaluate[F]
  )

  def evaluate[F[_]](state: State, cmd: Command)(implicit F: Async[F], T: Timer[F]): F[Either[Rejection, Event]] =
    (state, cmd) match {
      case (Current(revS, _), Boom(revC, message)) if revS == revC                  => F.raiseError(new RuntimeException(message))
      case (Initial, Boom(rev, message)) if rev == 0                                => F.raiseError(new RuntimeException(message))
      case (_, Boom(rev, _))                                                        => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, _), Never(revC)) if revS == revC                          => F.never
      case (Initial, Never(rev)) if rev == 0                                        => F.never
      case (_, Never(rev))                                                          => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Increment(rev, step)) if rev == 0                              => F.pure(Right(Incremented(1, step)))
      case (Initial, Increment(rev, _))                                             => F.pure(Left(InvalidRevision(rev)))
      case (Initial, IncrementAsync(rev, step, duration)) if rev == 0               =>
        T.sleep(duration) >> F.pure(Right(Incremented(1, step)))
      case (Initial, IncrementAsync(rev, _, _))                                     => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Initialize(rev)) if rev == 0                                   => F.pure(Right(Initialized(1)))
      case (Initial, Initialize(rev))                                               => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, _), Increment(revC, step)) if revS == revC                => F.pure(Right(Incremented(revS + 1, step)))
      case (Current(_, _), Increment(revC, _))                                      => F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, _), IncrementAsync(revC, step, duration)) if revS == revC =>
        T.sleep(duration) >> F.pure(Right(Incremented(revS + 1, step)))
      case (Current(_, _), IncrementAsync(revC, _, duration))                       =>
        T.sleep(duration) >> F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, _), Initialize(revC)) if revS == revC                     => F.pure(Right(Initialized(revS + 1)))
      case (Current(_, _), Initialize(rev))                                         => F.pure(Left(InvalidRevision(rev)))
    }

}
