package ch.epfl.bluebrain.nexus.storage

import com.typesafe.scalalogging.Logger

import scala.sys.process.ProcessLogger

/**
  * Simple [[scala.sys.process.ProcessLogger]] implementation backed by a [[StringBuilder]].
  *
  * @param cmd the command, used for logging purposes
  * @param arg an optional command argument
  * @note This expects a brief, single-line output.
  */
class StringProcessLogger(cmd: Seq[String], arg: Option[String]) extends ProcessLogger {

  private val logger = Logger(cmd.mkString(" "))

  private val builder = new StringBuilder

  override def out(s: => String): Unit = {
    builder.append(s)
    logger.debug(format(s, arg))
  }

  override def err(s: => String): Unit = {
    builder.append(s)
    logger.error(format(s, arg))
  }

  override def buffer[T](f: => T): T = f

  override def toString: String = builder.toString

  private def format(s: String, arg: Option[String]): String =
    arg match {
      case Some(a) => s"$a $s"
      case None    => s
    }

}

object StringProcessLogger {

  def apply(cmd: Seq[String], arg: String): StringProcessLogger = new StringProcessLogger(cmd, Some(arg))

  def apply(cmd: Seq[String]): StringProcessLogger = new StringProcessLogger(cmd, None)

}
