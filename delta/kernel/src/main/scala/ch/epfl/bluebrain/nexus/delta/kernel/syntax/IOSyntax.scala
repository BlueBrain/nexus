package ch.epfl.bluebrain.nexus.delta.kernel.syntax
import cats.Functor
import cats.effect.IO
import cats.implicits.catsSyntaxApplicativeError
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import com.typesafe.scalalogging.Logger
import monix.bio.{IO => BIO, Task, UIO}

trait IOSyntax {

  implicit final def bioRetryStrategyOps[E, A](io: BIO[E, A]): BIORetryStrategyOps[E, A] =
    new BIORetryStrategyOps[E, A](io)

  implicit final def bioFunctorOps[E, A, F[_]: Functor](io: BIO[E, F[A]]): BIOFunctorOps[E, A, F] = new BIOFunctorOps(
    io
  )

  implicit final def taskSyntaxLogErrors[A](task: Task[A]): TaskOps[A] = new TaskOps(task)

  implicit final def ioSyntaxLogErrors[A](io: IO[A]): IOOps[A] = new IOOps(io)

  implicit final def ioFunctorOps[A, F[_]: Functor](io: IO[F[A]]): IOFunctorOps[A, F] = new IOFunctorOps(io)
}

final class BIORetryStrategyOps[E, A](private val io: BIO[E, A]) extends AnyVal {

  /**
    * Apply the retry strategy on the provided IO
    */
  def retry(retryStrategy: RetryStrategy[E]): BIO[E, A] = RetryStrategy.use(io, retryStrategy)

}

final class BIOFunctorOps[E, A, F[_]: Functor](private val io: BIO[E, F[A]]) {

  /**
    * Map value of [[F]] wrapped in an [[IO]].
    *
    * @param f
    *   the mapping function
    * @return
    *   a new [[F]] with value being the result of applying [[f]] to the value of old [[F]]
    */
  def mapValue[B](f: A => B): BIO[E, F[B]] = io.map(_.map(f))
}

final class TaskOps[A](private val task: Task[A]) extends AnyVal {

  /**
    * Log errors before hiding them
    */
  def logAndDiscardErrors(action: String)(implicit logger: Logger): UIO[A] =
    task.onErrorHandleWith { ex =>
      UIO.delay(logger.warn(s"A Task is hiding an error while '$action'", ex)) >> UIO.terminate(ex)
    }
}

final class IOFunctorOps[A, F[_]: Functor](private val io: IO[F[A]]) {

  /**
    * Map value of [[F]] wrapped in an [[IO]].
    *
    * @param f
    *   the mapping function
    * @return
    *   a new [[F]] with value being the result of applying [[f]] to the value of old [[F]]
    */
  def mapValue[B](f: A => B): IO[F[B]] = io.map(_.map(f))
}

final class IOOps[A](private val task: IO[A]) extends AnyVal {
  def logErrors(action: String)(implicit logger: Logger): IO[A] =
    task.onError { ex =>
      IO.delay(logger.warn(s"Error during: '$action'", ex))
    }
}
