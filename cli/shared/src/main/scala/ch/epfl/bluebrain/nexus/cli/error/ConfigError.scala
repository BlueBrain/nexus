package ch.epfl.bluebrain.nexus.cli.error

import java.nio.file.Path

import cats.Show
import cats.syntax.show._
import pureconfig.error.ConfigReaderFailures

/**
  * Enumeration of all ConfigError types
  */
@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed trait ConfigError extends CliError

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object ConfigError {
  final case class ReadConvertError(failures: ConfigReaderFailures) extends ConfigError {
    val reason: String = "the application configuration failed to be loaded into a configuration object"
    val lines: List[String] =
      failures.toList.flatMap { f =>
        f.location match {
          case Some(loc) => f.description :: s"  file: ${loc.url.toString}" :: s"  line: ${loc.lineNumber}" :: Nil
          case None      => f.description :: Nil
        }
      }
  }

  // $COVERAGE-OFF$
  final case class ReadError(path: Path, message: String) extends ConfigError {
    val reason: String = s"the application configuration failed to be loaded from the path '$path'"
    val lines: List[String] = List(
      s"The following error arise while attempting to load the application configuration to disk: '$message'"
    )
  }

  final case class WritingFileError(path: Path, message: String) extends ConfigError {
    val reason: String = s"the application configuration failed to be saved on the path '$path'"
    val lines: List[String] = List(
      s"The following error arise while attempting to save the application configuration to disk: '$message'"
    )
  }

  final case object UserHomeNotDefined extends ConfigError {
    val reason: String = "the 'user.home' system property was not defined"
    val lines: List[String] = List(
      "The 'user.home' property is required for determining where the application",
      "configuration needs to be stored or read from. The JVM automatically detects",
      "the appropriate value from the provided environment, but in this case it",
      "could not.",
      "",
      s"${Console.GREEN}Solution${Console.RESET}: run the tool again forcing a value ('${Console.CYAN}-Duser.home=${Console.RESET}') to the",
      "expected system property."
    )
  }
  // $COVERAGE-ON$

  implicit val clientErrorShow: Show[ConfigError] = r => (r: CliError).show

}
