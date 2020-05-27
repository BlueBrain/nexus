package ch.epfl.bluebrain.nexus.cli.modules.config

import cats.effect.{ExitCode, Resource, Sync}
import ch.epfl.bluebrain.nexus.cli.AbstractCommand
import com.monovore.decline.Opts

/**
  * CLI configuration specific options.
  */
final class Config[F[_]: Sync](configShow: Opts[Resource[F, ConfigShow[F]]]) extends AbstractCommand[F] {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("config", "Read or write the tool configuration.") {
      show
    }

  private def show: Opts[F[ExitCode]] =
    Opts.subcommand("show", "Print the current configuration") {
      configShow.map(_.use(_.show))
    }

}

object Config {
  final def apply[F[_]: Sync](configShow: Opts[Resource[F, ConfigShow[F]]]): Config[F] =
    new Config(configShow)
}
