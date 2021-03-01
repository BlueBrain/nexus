package ch.epfl.bluebrain.nexus.delta.kernel.syntax
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}

trait TaskSyntax {
  implicit final def taskSyntaxLogErrors[A](task: Task[A]): TaskOps[A] = new TaskOps(task)
}

final class TaskOps[A](private val task: Task[A]) extends AnyVal {

  /**
    * Log errors before hiding them
    */
  def logAndDiscardErrors(action: String)(implicit logger: Logger): UIO[A] =
    task.onErrorHandleWith { ex =>
      UIO.delay(logger.warn(s"A Task is hiding an error while '$action'", ex)) >> IO.terminate(ex)
    }

}
