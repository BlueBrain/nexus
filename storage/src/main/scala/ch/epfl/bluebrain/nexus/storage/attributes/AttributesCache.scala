package ch.epfl.bluebrain.nexus.storage.attributes

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{ask, AskTimeoutException}
import akka.util.Timeout
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.storage.File.FileAttributes
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, OperationTimedOut}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesCacheActor.Protocol._
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.DigestConfig

import java.nio.file.Path
import java.time.Clock
import scala.util.control.NonFatal

trait AttributesCache {

  /**
    * Fetches the file attributes for the provided absFilePath. If the digest is being computed or is going to be
    * computed, a Digest.empty is returned
    *
    * @param filePath
    *   the absolute file path
    * @return
    *   the file attributes wrapped in the effect type F
    */
  def get(filePath: Path): IO[FileAttributes]

  /**
    * Computes the file attributes and stores them asynchronously on the cache
    *
    * @param filePath
    *   the absolute file path
    * @param algorithm
    *   the digest algorithm
    */
  def asyncComputePut(filePath: Path, algorithm: String): Unit
}

object AttributesCache {

  private val logger = Logger[this.type]

  def apply[Source](implicit
      system: ActorSystem,
      clock: Clock,
      tm: Timeout,
      computation: AttributesComputation[Source],
      config: DigestConfig
  ): AttributesCache =
    apply(system.actorOf(AttributesCacheActor.props(computation)))

  private[attributes] def apply[F[_]](
      underlying: ActorRef
  )(implicit tm: Timeout): AttributesCache =
    new AttributesCache {
      override def get(filePath: Path): IO[FileAttributes] =
        IO.fromFuture(IO.delay(underlying ? Get(filePath)))
          .flatMap[FileAttributes] {
            case attributes: FileAttributes => IO.pure(attributes)
            case other                      =>
              logger.error(s"Received unexpected reply from the file attributes cache: '$other'") >>
                IO.raiseError(InternalError("Unexpected reply from the file attributes cache"))
          }
          .recoverWith {
            case _: AskTimeoutException =>
              IO.raiseError(OperationTimedOut("reply from the file attributes cache timed out"))
            case NonFatal(th)           =>
              logger.error(th)("Exception caught while exchanging messages with the file attributes cache") >>
                IO.raiseError(
                  InternalError("Exception caught while exchanging messages with the file attributes cache")
                )
          }

      override def asyncComputePut(filePath: Path, algorithm: String): Unit =
        underlying ! Compute(filePath)

    }
}
