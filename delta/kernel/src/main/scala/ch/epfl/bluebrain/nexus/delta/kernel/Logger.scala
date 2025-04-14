package ch.epfl.bluebrain.nexus.delta.kernel

import cats.effect.IO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger as Log4CatsLogger, LoggerName}

import scala.reflect.{classTag, ClassTag}

object Logger {

  def apply[A: ClassTag]: Log4CatsLogger[IO] = {
    implicit val loggerName: LoggerName = LoggerName(classTag[A].runtimeClass.getName.stripSuffix("$"))
    Slf4jLogger.getLogger[IO]
  }
}
