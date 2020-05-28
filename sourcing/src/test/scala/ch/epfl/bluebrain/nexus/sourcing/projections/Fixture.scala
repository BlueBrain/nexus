package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.journal.{Tagged, WriteEventAdapter}
import cats.effect.{IO, Sync}
import cats.effect.concurrent.Ref
import com.github.ghik.silencer.silent

object Fixture {

  sealed trait Event
  final case object Executed           extends Event
  final case object OtherExecuted      extends Event
  final case object AnotherExecuted    extends Event
  final case object YetAnotherExecuted extends Event
  final case object RetryExecuted      extends Event
  final case object IgnoreExecuted     extends Event
  final case object NotDiscarded       extends Event
  final case object Discarded          extends Event

  sealed trait EventTransform
  final case object ExecutedTransform           extends EventTransform
  final case object OtherExecutedTransform      extends EventTransform
  final case object AnotherExecutedTransform    extends EventTransform
  final case object YetAnotherExecutedTransform extends EventTransform
  final case object RetryExecutedTransform      extends EventTransform
  final case object IgnoreExecutedTransform     extends EventTransform
  final case object NotDiscardedTransform       extends EventTransform
  final case object DiscardedTransform          extends EventTransform

  sealed trait Cmd
  final case object Execute           extends Cmd
  final case object ExecuteOther      extends Cmd
  final case object ExecuteAnother    extends Cmd
  final case object ExecuteYetAnother extends Cmd
  final case object ExecuteRetry      extends Cmd
  final case object ExecuteIgnore     extends Cmd

  sealed trait State
  final case object Perpetual extends State

  sealed trait Rejection
  final case object Reject extends Rejection

  class TaggingAdapter extends WriteEventAdapter {
    override def manifest(event: Any): String = ""
    override def toJournal(event: Any): Any = event match {
      case Executed           => Tagged(event, Set("executed"))
      case OtherExecuted      => Tagged(event, Set("other"))
      case AnotherExecuted    => Tagged(event, Set("another"))
      case YetAnotherExecuted => Tagged(event, Set("yetanother"))
      case RetryExecuted      => Tagged(event, Set("retry"))
      case IgnoreExecuted     => Tagged(event, Set("ignore"))
      case NotDiscarded       => Tagged(event, Set("discard"))
      case Discarded          => Tagged(event, Set("discard"))

    }
  }

  val initial: State = Perpetual
  @silent
  def next(state: State, event: Event): State = Perpetual
  @silent
  def eval(state: State, cmd: Cmd): IO[Either[Rejection, Event]] = cmd match {
    case Execute           => IO.pure(Right(Executed))
    case ExecuteOther      => IO.pure(Right(OtherExecuted))
    case ExecuteAnother    => IO.pure(Right(AnotherExecuted))
    case ExecuteYetAnother => IO.pure(Right(YetAnotherExecuted))
    case ExecuteRetry      => IO.pure(Right(RetryExecuted))
    case ExecuteIgnore     => IO.pure(Right(IgnoreExecuted))

  }

  def memoize[F[_], A](fa: F[A])(implicit F: Sync[F]): F[F[A]] = {
    import cats.implicits._
    for {
      ref <- Ref[F].of(fa.attempt)
      _   <- ref.update(_.flatTap(a => ref.set(a.pure[F])))
    } yield ref.get.flatten.rethrow
  }
}
