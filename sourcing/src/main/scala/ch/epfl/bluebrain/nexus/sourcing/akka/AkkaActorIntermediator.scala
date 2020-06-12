package ch.epfl.bluebrain.nexus.sourcing.akka

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.{ContextShift, Effect, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.sourcing.akka.Msg._
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

import scala.reflect.ClassTag

/**
  * Interface between the client and the actor for sending messages to an Akka Actor.
  *
  * @param name       name of the actor
  * @param selection  an actor selection strategy for a name and an identifier
  * @param askTimeout the time to wait for actor ask queries
  */
abstract private[akka] class AkkaActorIntermediator[F[_]: Timer](
    name: String,
    selection: ActorRefSelection[F],
    askTimeout: Timeout
)(implicit F: Effect[F], as: ActorSystem, policy: RetryPolicy[F]) {

  implicit private[akka] val contextShift: ContextShift[IO]        = IO.contextShift(as.dispatcher)
  implicit private[akka] def noop[A]: (A, RetryDetails) => F[Unit] = retry.noop[F, A]
  implicit private val timeout: Timeout                            = askTimeout

  private[akka] def send[M <: Msg, Reply, A](id: String, msg: M, f: Reply => A)(implicit
      Reply: ClassTag[Reply]
  ): F[A] =
    selection(name, id).flatMap { ref =>
      val future = IO(ref ? msg)
      val fa     = IO.fromFuture(future).to[F]
      fa.flatMap[A] {
          case Reply(value)                     => F.pure(f(value))
          case te: TypeError                    => F.raiseError(te)
          case um: UnexpectedMsgId              => F.raiseError(um)
          case cet: CommandEvaluationTimeout[_] => F.raiseError(cet)
          case cee: CommandEvaluationError[_]   => F.raiseError(cee)
          case other                            => F.raiseError(TypeError(id, Reply.runtimeClass.getSimpleName, other))
        }
        .retryingOnAllErrors[Throwable]
    }
}
