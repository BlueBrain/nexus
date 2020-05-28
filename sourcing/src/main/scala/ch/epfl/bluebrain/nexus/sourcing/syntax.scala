package ch.epfl.bluebrain.nexus.sourcing

import cats.implicits._
import cats.{Applicative, Functor}
import ch.epfl.bluebrain.nexus.sourcing.EvaluationSyntax._

object syntax {

  implicit final def evaluationSyntax[State, Command](
      evaluate: (State, Command) => State
  ): EvaluationOps[State, Command] =
    new EvaluationOps[State, Command](evaluate)

  implicit final def evaluationFSyntax[F[_], State, Command](
      evaluate: (State, Command) => F[State]
  ): EvaluationFOps[F, State, Command] =
    new EvaluationFOps[F, State, Command](evaluate)
}

private[sourcing] object EvaluationSyntax {

  final class EvaluationOps[State, Command](private val evaluate: (State, Command) => State) extends AnyVal {
    def toEitherF[F[_]](implicit F: Applicative[F]): (State, Command) => F[Either[Unit, State]] =
      (st, cmd) => F.pure(Right(evaluate(st, cmd)))
  }

  final class EvaluationFOps[F[_], State, Command](private val evaluate: (State, Command) => F[State]) extends AnyVal {
    def toEither(implicit F: Functor[F]): (State, Command) => F[Either[Unit, State]] =
      (st, cmd) => evaluate(st, cmd).map(Right.apply)
  }

}
