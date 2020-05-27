package ch.epfl.bluebrain.nexus.cli

import cats.effect.ExitCode
import com.monovore.decline.Opts

trait AbstractCommand[F[_]] {
  def subcommand: Opts[F[ExitCode]]
}
