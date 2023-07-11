package ch.epfl.bluebrain.nexus.delta.kernel

import monix.bio.{Task, UIO}
import org.typelevel.log4cats.{Logger => Log4CatsLogger}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.reflect.ClassTag

class Logger[A](implicit classTag: ClassTag[A]) extends Log4CatsLogger[UIO] {

  private val scalaLogger                  = com.typesafe.scalalogging.Logger[A]
  private val logger: Log4CatsLogger[Task] = Slf4jLogger.getLoggerFromSlf4j[Task](scalaLogger.underlying)

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

object Logger {
  def apply[A: ClassTag]: Logger[A] = new Logger[A]()

}
