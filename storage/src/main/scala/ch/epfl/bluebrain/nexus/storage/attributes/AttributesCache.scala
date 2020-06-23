package ch.epfl.bluebrain.nexus.storage.attributes

import java.nio.file.Path
import java.time.Clock

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import cats.effect.{ContextShift, Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.storage.File.FileAttributes
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, OperationTimedOut}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesCacheActor.Protocol._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig
import com.typesafe.scalalogging.Logger

import scala.util.control.NonFatal

trait AttributesCache[F[_]] {

  /**
    * Fetches the file attributes for the provided absFilePath.
    * If the digest is being computed or is going to be computed, a Digest.empty is returned
    *
    * @param filePath the absolute file path
    * @return the file attributes wrapped in the effect type F
    */
  def get(filePath: Path): F[FileAttributes]

  /**
    * Computes the file attributes and stores them asynchronously on the cache
    *
    * @param filePath  the absolute file path
    * @param algorithm the digest algorithm
    */
  def asyncComputePut(filePath: Path, algorithm: String): Unit
}

object AttributesCache {
  private[this] val logger = Logger[this.type]

  def apply[F[_], Source](implicit
      system: ActorSystem,
      clock: Clock,
      tm: Timeout,
      F: Effect[F],
      computation: AttributesComputation[F, Source],
      config: DigestConfig
  ): AttributesCache[F] =
    apply(system.actorOf(AttributesCacheActor.props(computation)))

  private[attributes] def apply[F[_]](
      underlying: ActorRef
  )(implicit system: ActorSystem, tm: Timeout, F: Effect[F]): AttributesCache[F] =
    new AttributesCache[F] {
      implicit private val contextShift: ContextShift[IO] = IO.contextShift(system.dispatcher)

      override def get(filePath: Path): F[FileAttributes] =
        IO.fromFuture(IO.shift(system.dispatcher) >> IO(underlying ? Get(filePath)))
          .to[F]
          .flatMap[FileAttributes] {
            case attributes: FileAttributes => F.pure(attributes)
            case other                      =>
              logger.error(s"Received unexpected reply from the file attributes cache: '$other'")
              F.raiseError(InternalError("Unexpected reply from the file attributes cache"))
          }
          .recoverWith {
            case _: AskTimeoutException =>
              F.raiseError(OperationTimedOut("reply from the file attributes cache timed out"))
            case NonFatal(th)           =>
              logger.error("Exception caught while exchanging messages with the file attributes cache", th)
              F.raiseError(InternalError("Exception caught while exchanging messages with the file attributes cache"))
          }

      override def asyncComputePut(filePath: Path, algorithm: String): Unit =
        underlying ! Compute(filePath)

    }
}
