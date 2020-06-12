package ch.epfl.bluebrain.nexus.cli.modules.config

import cats.Parallel
import cats.effect.{ConcurrentEffect, ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import ch.epfl.bluebrain.nexus.cli.{AbstractCommand, Console}
import com.monovore.decline.Opts
import com.typesafe.config.ConfigRenderOptions
import distage.TagK
import izumi.distage.model.recursive.LocatorRef
import pureconfig.ConfigWriter

/**
  * CLI configuration specific options.
  */
final class Config[F[_]: Timer: Parallel: ContextShift: TagK](locatorOpt: Option[LocatorRef])(implicit
    F: ConcurrentEffect[F]
) extends AbstractCommand[F](locatorOpt) {

  def subcommand: Opts[F[ExitCode]] =
    Opts.subcommand("config", "Read or write the tool configuration.") {
      show
    }

  private def show: Opts[F[ExitCode]] =
    Opts.subcommand("show", "Print the current configuration") {
      locatorResource.map {
        _.use { locator =>
          val console = locator.get[Console[F]]
          val cfg     = locator.get[AppConfig]
          console.println(renderConfig(cfg)).as(ExitCode.Success)
        }
      }
    }

  private def renderConfig(cfg: AppConfig): String = {
    val opts = ConfigRenderOptions.concise().setComments(false).setJson(false).setFormatted(true)
    ConfigWriter[AppConfig].to(cfg).render(opts)
  }
}

object Config {
  final def apply[F[_]: TagK: ConcurrentEffect: Timer: Parallel: ContextShift](
      locatorOpt: Option[LocatorRef] = None
  ): Config[F] =
    new Config[F](locatorOpt)
}
