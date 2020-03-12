package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.{ContextShift, ExitCode, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.error.CliError
import ch.epfl.bluebrain.nexus.cli.postgres.cli.Cli
import monix.catnap.SchedulerEffect
import monix.eval.{Task, TaskApp}

// $COVERAGE-OFF$
object Main extends TaskApp {

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val cs: ContextShift[Task] = SchedulerEffect.contextShift[Task](scheduler)
    implicit val tm: Timer[Task]        = SchedulerEffect.timer[Task](scheduler)
    Cli(args, sys.env).recoverWith {
      case err: CliError => Task.delay(println(err.show)).as(ExitCode.Error)
    }
  }

}
