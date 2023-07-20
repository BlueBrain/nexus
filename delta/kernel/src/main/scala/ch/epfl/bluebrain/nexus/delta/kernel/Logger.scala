package ch.epfl.bluebrain.nexus.delta.kernel

import monix.bio.{Task, UIO}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger => Log4CatsLogger, LoggerName}

import scala.reflect.{classTag, ClassTag}

/**
  * Wrapper class that is used to be able to create log statements as UIOs. It is needed because "any type class from
  * Sync and above will only work with IO[Throwable, A]" (see https://bio.monix.io/docs/cats-effect#sync-and-above)
  */
trait Logger extends Log4CatsLogger[UIO]

object Logger {
  def apply[A: ClassTag]: Logger = new Logger {
    implicit private val loggerName: LoggerName = LoggerName(classTag[A].runtimeClass.getName.stripSuffix("$"))
    private val logger: Log4CatsLogger[Task]    = Slf4jLogger.getLogger[Task]

    override def error(t: Throwable)(message: => String): UIO[Unit] =
      logger.error(t)(message).hideErrors

    override def warn(t: Throwable)(message: => String): UIO[Unit] =
      logger.warn(t)(message).hideErrors

    override def info(t: Throwable)(message: => String): UIO[Unit] =
      logger.info(t)(message).hideErrors

    override def debug(t: Throwable)(message: => String): UIO[Unit] =
      logger.debug(t)(message).hideErrors

    override def trace(t: Throwable)(message: => String): UIO[Unit] =
      logger.trace(t)(message).hideErrors

    override def error(message: => String): UIO[Unit] =
      logger.error(message).hideErrors

    override def warn(message: => String): UIO[Unit] =
      logger.warn(message).hideErrors

    override def info(message: => String): UIO[Unit] =
      logger.info(message).hideErrors

    override def debug(message: => String): UIO[Unit] =
      logger.debug(message).hideErrors

    override def trace(message: => String): UIO[Unit] =
      logger.trace(message).hideErrors
  }
}
