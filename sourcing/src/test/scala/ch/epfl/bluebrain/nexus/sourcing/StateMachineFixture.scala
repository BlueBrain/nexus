package ch.epfl.bluebrain.nexus.sourcing

import cats.effect.{Async, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.Command._
import ch.epfl.bluebrain.nexus.sourcing.Rejection.InvalidRevision
import ch.epfl.bluebrain.nexus.sourcing.State.{Current, Initial}

object StateMachineFixture {

  val initialState: State = State.Initial

  def evaluate[F[_]](state: State, cmd: Command)(implicit F: Async[F], T: Timer[F]): F[Either[Rejection, State]] =
    (state, cmd) match {
      case (Current(revS, _), Boom(revC, message)) if revS == revC => F.raiseError(new RuntimeException(message))
      case (Initial, Boom(rev, message)) if rev == 0               => F.raiseError(new RuntimeException(message))
      case (_, Boom(rev, _))                                       => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, _), Never(revC)) if revS == revC         => F.never
      case (Initial, Never(rev)) if rev == 0                       => F.never
      case (_, Never(rev))                                         => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Increment(rev, step)) if rev == 0             => F.pure(Right(State.Current(1, step)))
      case (Initial, Increment(rev, _))                            => F.pure(Left(InvalidRevision(rev)))
      case (Initial, IncrementAsync(rev, step, duration)) if rev == 0 =>
        T.sleep(duration) >> F.pure(Right(State.Current(1, step)))
      case (Initial, IncrementAsync(rev, _, _))   => F.pure(Left(InvalidRevision(rev)))
      case (Initial, Initialize(rev)) if rev == 0 => F.pure(Right(State.Current(1, 0)))
      case (Initial, Initialize(rev))             => F.pure(Left(InvalidRevision(rev)))
      case (Current(revS, value), Increment(revC, step)) if revS == revC =>
        F.pure(Right(State.Current(revS + 1, value + step)))
      case (Current(_, _), Increment(revC, _)) => F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, value), IncrementAsync(revC, step, duration)) if revS == revC =>
        T.sleep(duration) >> F.pure(Right(State.Current(revS + 1, value + step)))
      case (Current(_, _), IncrementAsync(revC, _, duration)) =>
        T.sleep(duration) >> F.pure(Left(InvalidRevision(revC)))
      case (Current(revS, _), Initialize(revC)) if revS == revC => F.pure(Right(State.Current(revS + 1, 0)))
      case (Current(_, _), Initialize(rev))                     => F.pure(Left(InvalidRevision(rev)))
    }

}
