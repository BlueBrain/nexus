package ch.epfl.bluebrain.nexus.cli

import java.nio.file.Path

import cats.Show
import cats.effect.Sync
import cats.implicits._
import org.http4s.{Response, Status}
import pureconfig.error.ConfigReaderFailures

import scala.Console._
import scala.util.Try

sealed trait CliError extends Exception {
  override def fillInStackTrace(): CliError = this
  override def getMessage: String           = s"Reason: '$reason'"

  def reason: String
  def lines: List[String]
  def footer: Option[String] = None

  def asString: String =
    s"""🔥  An error occurred because '$RED$reason$RESET', details:
       |🔥
       |${lines.map(l => s"🔥    $l").mkString("\n")}
       |${footer.map(l => s"\n🔥  $l").mkString("\n")}""".stripMargin
}

object CliError {

  /**
    * Enumeration of possible Client errors.
    */

  sealed trait ClientError extends CliError

  object ClientError {

    /**
      * Attempt to construct a [[ClientError]] from the passed HTTP status code and message
      *
      * @return Some(clientError) if the code is not Successful, false otherwise
      */
    def apply(code: Status, message: String): Option[ClientError] =
      Try(unsafe(code, message)).toOption

    /**
      * Construct a [[ClientError]] from the passed HTTP status code and message
      *
      * @throws IllegalArgumentException if the code is a Successful HTTP Status code is 2xx
      */
    def unsafe(code: Status, message: String): ClientError =
      code.responseClass match {
        case Status.Successful  =>
          throw new IllegalArgumentException(s"Successful code '$code cannot be converted to a ClientError'")
        case Status.ClientError =>
          ClientStatusError(code, message)
        case Status.ServerError => ServerStatusError(code, message)
        case _                  =>
          UnexpectedStatusError(code, message)
      }

    /**
      * A serialization error when attempting to cast response.
      *
      * @param message  the error message
      * @param tpe      the type into which the serialization was attempted
      * @param original the optionally available original payload
      */
    final case class SerializationError(message: String, tpe: String, original: Option[String] = None)
        extends ClientError {
      val reason: String      = s"an HTTP response could not be converted to type '$tpe'"
      val lines: List[String] = List(s"the serialization failed due to '$message'") ++
        original.map(orig => s"The message attempted to serialized was: '$orig'.").toList
    }

    /**
      * A Client status error (HTTP status codes 4xx).
      *
      * @param code    the HTTP status code
      * @param message the error message
      */
    final case class ClientStatusError(code: Status, message: String) extends ClientError {
      val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
      val lines: List[String] = List(s"The request failed due to '$message'")
    }

    /**
      * A server status error (HTTP status codes 5xx).
      *
      * @param code    the HTTP status code
      * @param message the error message
      */
    final case class ServerStatusError(code: Status, message: String) extends ClientError {
      val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
      val lines: List[String] = List(s"The request failed due to '$message'")
    }

    /**
      * Some other response error which is not 4xx nor 5xx
      *
      * @param code    the HTTP status code
      * @param message the error message
      */
    final case class UnexpectedStatusError(code: Status, message: String) extends ClientError {
      val reason: String      = s"an HTTP response that should have been successful, returned the HTTP status code '$code'"
      val lines: List[String] = List(s"The request failed due to '$message'")
    }

    /**
      * An unexpected error thrown by the client
      *
      * @param message the error message
      */
    final case class Unexpected(message: String) extends ClientError {
      val reason: String      = s"an HTTP response that should have been successful, failed unexpectedly"
      val lines: List[String] = List(s"The request failed due to '$message'")
    }

    def errorOr[F[_]: Sync, A](successF: Response[F] => F[ClientErrOr[A]]): Response[F] => F[ClientErrOr[A]] = {
      case Status.Successful(r)  => successF(r)
      case Status.ClientError(r) => r.bodyText.compile.string.map(s => Left(ClientStatusError(r.status, s)))
      case Status.ServerError(r) => r.bodyText.compile.string.map(s => Left(ServerStatusError(r.status, s)))
      case r                     => r.bodyText.compile.string.map(s => Left(UnexpectedStatusError(r.status, s)))
    }

    implicit val clientErrorShow: Show[ClientError] = Show[CliError].narrow

  }

  /**
    * Enumeration of all ConfigError types
    */

  sealed trait ConfigError extends CliError

  object ConfigError {
    final case class ReadConvertError(failures: ConfigReaderFailures) extends ConfigError {
      val reason: String      = "the application configuration failed to be loaded into a configuration object"
      val lines: List[String] =
        failures.toList.flatMap { f =>
          f.origin match {
            case Some(o) => f.description :: s"  file: ${o.url.toString}" :: s"  line: ${o.lineNumber}" :: Nil
            case None    => f.description :: Nil
          }
        }
    }

    // $COVERAGE-OFF$
    final case class ReadError(path: Path, message: String) extends ConfigError {
      val reason: String      = s"the application configuration failed to be loaded from the path '$path'"
      val lines: List[String] = List(
        s"The following error arise while attempting to load the application configuration to disk: '$message'"
      )
    }

    final case class WritingFileError(path: Path, message: String) extends ConfigError {
      val reason: String      = s"the application configuration failed to be saved on the path '$path'"
      val lines: List[String] = List(
        s"The following error arise while attempting to save the application configuration to disk: '$message'"
      )
    }

    final case object UserHomeNotDefined extends ConfigError {
      val reason: String      = "the 'user.home' system property was not defined"
      val lines: List[String] = List(
        "The 'user.home' property is required for determining where the application",
        "configuration needs to be stored or read from. The JVM automatically detects",
        "the appropriate value from the provided environment, but in this case it",
        "could not.",
        "",
        s"${GREEN}Solution$RESET: run the tool again forcing a value ('$CYAN-Duser.home=$RESET') to the",
        "expected system property."
      )
    }
    // $COVERAGE-ON$

    implicit val clientErrorShow: Show[ConfigError] = Show[CliError].narrow

  }

  implicit final val cliErrorShow: Show[CliError] = Show.show(_.asString)
}
