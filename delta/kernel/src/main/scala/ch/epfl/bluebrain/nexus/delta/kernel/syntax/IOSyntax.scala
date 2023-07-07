package ch.epfl.bluebrain.nexus.delta.kernel.syntax
import cats.Functor
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}

trait IOSyntax {

  implicit final def ioRetryStrategyOps[E, A](io: IO[E, A]): IORetryStrategyOps[E, A] =
    new IORetryStrategyOps[E, A](io)

  implicit final def ioFunctorOps[E, A, F[_]: Functor](io: IO[E, F[A]]): IOFunctorOps[E, A, F] = new IOFunctorOps(io)

  implicit final def taskSyntaxLogErrors[A](task: Task[A]): TaskOps[A] = new TaskOps(task)
}

final class IORetryStrategyOps[E, A](private val io: IO[E, A]) extends AnyVal {

  /**
    * Apply the retry strategy on the provided IO
    */
  def retry(retryStrategy: RetryStrategy[E]): IO[E, A] = RetryStrategy.use(io, retryStrategy)

}

final class IOFunctorOps[E, A, F[_]: Functor](private val io: IO[E, F[A]]) {

  /**
    * Map value of [[F]] wrapped in an [[IO]].
    *
    * @param f
    *   the mapping function
    * @return
    *   a new [[F]] with value being the result of applying [[f]] to the value of old [[F]]
    */
  def mapValue[B](f: A => B): IO[E, F[B]] = io.map(_.map(f))
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
