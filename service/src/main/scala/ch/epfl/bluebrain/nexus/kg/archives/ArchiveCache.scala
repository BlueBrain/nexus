package ch.epfl.bluebrain.nexus.kg.archives

import akka.actor.{ActorSystem, NotInfluenceReceiveTimeout}
import cats.Monad
import cats.data.OptionT
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.kg.archives.ArchiveCache._
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.resources.ResId
import ch.epfl.bluebrain.nexus.sourcing.StateMachine
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.AkkaStateMachine
import retry.RetryPolicy

class ArchiveCache[F[_]: Monad](ref: StateMachine[F, String, State, Command, Unit]) {

  /**
    * Retrieves the [[Archive]] from the cache.
    *
    * @param resId the resource id for which imports are looked up
    * @return Some(archive) when found on the cache, None otherwise wrapped on the F[_] type
    */
  def get(resId: ResId): OptionT[F, Archive] =
    OptionT(ref.currentState(resId.show))

  /**
    * Writes the passed [[Archive]] to the cache. It will be invalidated after a time configured
    * at ''cfg.cache.invalidation.lapsedSinceLastInteraction'' where cfg is [[ArchivesConfig]].
    *
    * @param value the archive to write
    * @return Some(archive) when successfully added to the cache, None if it already existed in the cache wrapped on the F[_] type
    */
  def put(value: Archive): OptionT[F, Archive] =
    OptionT(ref.evaluate(value.resId.show, Write(value)).map(_.toOption.flatten))

}

object ArchiveCache {

  private[archives] type State   = Option[Archive]
  private[archives] type Command = Write
  final private[archives] case class Write(bundle: Archive) extends NotInfluenceReceiveTimeout

  final def apply[F[_]: Timer](implicit as: ActorSystem, cfg: ArchivesConfig, F: Effect[F]): F[ArchiveCache[F]] = {
    implicit val retryPolicy: RetryPolicy[F] = cfg.cache.retry.retryPolicy[F]
    val invalidationStrategy                 = StopStrategy.lapsedSinceLastInteraction[State, Command](cfg.cacheInvalidateAfter)

    val evaluate: (State, Command) => F[Either[Unit, State]] = {
      case (None, Write(bundle)) => F.pure(Right(Some(bundle)))
      case (Some(_), _)          => F.pure(Left(())) // It already exists, so we don't want to replace it
    }

    AkkaStateMachine
      .sharded[F]("archives", None, evaluate, invalidationStrategy, cfg.cache.akkaStateMachineConfig, cfg.cache.shards)
      .map(new ArchiveCache[F](_))
  }
}
