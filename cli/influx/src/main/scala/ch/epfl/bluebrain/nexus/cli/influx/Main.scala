package ch.epfl.bluebrain.nexus.cli.influx

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}

object Main extends TaskApp {

  override def run(args: List[String]): Task[ExitCode] =
    Task.pure(ExitCode.Success)

}
