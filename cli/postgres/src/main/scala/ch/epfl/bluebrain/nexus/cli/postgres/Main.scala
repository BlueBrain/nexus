package ch.epfl.bluebrain.nexus.cli.postgres

import cats.effect.ExitCode
import monix.eval.{Task, TaskApp}

// $COVERAGE-OFF$
object Main extends TaskApp {

  override def run(args: List[String]): Task[ExitCode] =
    Task.pure(ExitCode.Success)

}
