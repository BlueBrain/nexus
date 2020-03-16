package ch.epfl.bluebrain.nexus.cli.influxdb

import cats.effect.ExitCode
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.CliError
import monix.eval.{Task, TaskApp}

object Main extends TaskApp {

  override def run(args: List[String]): Task[ExitCode] = {
    Cli[Task].command(args, sys.env).recoverWith {
      case err: CliError => Task.delay(println(err.show)).as(ExitCode.Error)
    }
  }

}
