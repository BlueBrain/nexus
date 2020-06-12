package ch.epfl.bluebrain.nexus.cli

import java.nio.file.{Path, Paths}

import cats.data.{Validated, ValidatedNel}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.sse.{BearerToken, Offset}
import com.monovore.decline.{Argument, Opts}
import org.http4s.Uri

import scala.util.Try

/**
  * Collection of command line options.
  */
object CliOpts extends OptsInstances {

  val token: Opts[Option[BearerToken]] = Opts
    .option[String](
      long = "token",
      help = "The token to use when interacting with the Nexus API; " +
        "a 'none' string value will remove any preconfigured token."
    )
    .validate("Token must be a non empty string") { !_.isBlank }
    .map {
      case "none" => None
      case value  => Some(BearerToken(value))
    }

  val offset: Opts[Option[Offset]] = Opts
    .option[String](
      long = "offset",
      help = "The offset to use when starting the event replay; " +
        "a 'none' string value will discard any saved offset."
    )
    .map(_.trim)
    .mapValidated {
      case "none" => Validated.validNel(None)
      case value  => Offset(value).toRight("Offset is not valid").map(o => Some(o)).toValidatedNel
    }

  val endpoint: Opts[Uri] = Opts
    .option[Uri](
      long = "endpoint",
      help = "The base address of the Nexus API"
    )

  val envConfig: Opts[Path] = Opts
    .option[Path](
      long = "env",
      help = "The environment configuration file"
    )

  val postgresConfig: Opts[Path] = Opts
    .option[Path](
      long = "config",
      help = "The postgres configuration file"
    )

  val influxConfig: Opts[Path] = Opts
    .option[Path](
      long = "config",
      help = "The influx configuration file"
    )

}

trait OptsInstances {
  implicit protected val uriArgument: Argument[Uri] = new Argument[Uri] {
    override def read(string: String): ValidatedNel[String, Uri] =
      Uri
        .fromString(string)
        .leftMap(_ => s"Invalid Uri: '$string'")
        .ensure(s"Invalid Uri: '$string'")(uri => uri.scheme.isDefined)
        .toValidatedNel
    override val defaultMetavar: String                          = "http://..."
  }

  implicit protected val pathArgument: Argument[Path] = new Argument[Path] {
    override def read(string: String): ValidatedNel[String, Path] =
      Try(Paths.get(string)).toOption.toRight(s"Invalid file path '$string'").toValidatedNel
    override val defaultMetavar: String                           = "../file.conf"
  }
}
