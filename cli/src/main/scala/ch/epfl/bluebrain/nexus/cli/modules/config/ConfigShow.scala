package ch.epfl.bluebrain.nexus.cli.modules.config

import cats.Functor
import cats.effect.ExitCode
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.cli.Console
import ch.epfl.bluebrain.nexus.cli.config.AppConfig
import com.typesafe.config.ConfigRenderOptions
import pureconfig.ConfigWriter

final class ConfigShow[F[_]: Functor](console: Console[F], cfg: AppConfig) {
  def show: F[ExitCode] =
    console.println(renderConfig(cfg)).as(ExitCode.Success)

  private def renderConfig(cfg: AppConfig): String = {
    val opts = ConfigRenderOptions.concise().setComments(false).setJson(false).setFormatted(true)
    ConfigWriter[AppConfig].to(cfg).render(opts)
  }
}
