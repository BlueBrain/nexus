package ch.epfl.bluebrain.nexus.sourcing

import cats.effect.{Async, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.Command._
import ch.epfl.bluebrain.nexus.sourcing.Event.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.sourcing.Rejection.InvalidRevision
import ch.epfl.bluebrain.nexus.sourcing.State.{Current, Initial}

object AggregateFixture {

  val initialState: State = State.Initial

  val next: (State, Event) => State = {
    case (Initial, Incremented(1, step))             => State.Current(1, step)
    case (Initial, Initialized(rev))                 => State.Current(rev, 0)
    case (Current(_, value), Incremented(rev, step)) => State.Current(rev, value + step)
    case (Current(_, _), Initialized(rev))           => State.Current(rev, 0)
    case (other, _)                                  => other
  }

  def evaluate[F[_]](state: State, cmd: Command)(implicit F: Async[F], T: Timer[F]): F[Either[Rejection, Event]] =
    (state, cmd) match {
      case (Current(revS, _), Boom(revC, message)) if revS == revC => F.raiseError(new RuntimeException(message))
      case (Initial, Boom(rev, message)) if rev == 0               => F.raiseError(new RuntimeException(message))
      case (_, Boom(rev, _))                                       => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, _), Never(revC)) if revS == revC         => F.never
      case (Initial, Never(rev)) if rev == 0                       => F.never
      case (_, Never(rev))                                         => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Increment(rev, step)) if rev == 0             => F.pure(Right(Incremented(1, step)))
      case (Initial, Increment(rev, _))                            => F.pure(Left(InvalidRevision(rev)))
      case (Initial, IncrementAsync(rev, step, duration)) if rev == 0 =>
        T.sleep(duration) >> F.pure(Right(Incremented(1, step)))
      case (Initial, IncrementAsync(rev, _, _))                      => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Initialize(rev)) if rev == 0                    => F.pure(Right(Initialized(1)))
      case (Initial, Initialize(rev))                                => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, _), Increment(revC, step)) if revS == revC => F.pure(Right(Incremented(revS + 1, step)))
      case (Current(_, _), Increment(revC, _))                       => F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, _), IncrementAsync(revC, step, duration)) if revS == revC =>
        T.sleep(duration) >> F.pure(Right(Incremented(revS + 1, step)))
      case (Current(_, _), IncrementAsync(revC, _, duration)) =>
        T.sleep(duration) >> F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, _), Initialize(revC)) if revS == revC => F.pure(Right(Initialized(revS + 1)))
      case (Current(_, _), Initialize(rev))                     => F.pure(Left(InvalidRevision(rev)))
    }

}
