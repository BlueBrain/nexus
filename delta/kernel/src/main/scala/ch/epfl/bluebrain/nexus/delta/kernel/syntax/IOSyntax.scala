package ch.epfl.bluebrain.nexus.delta.kernel.syntax
import cats.Functor
import cats.effect.IO
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import org.typelevel.log4cats.{Logger => Log4CatsLogger}

import scala.reflect.ClassTag

trait IOSyntax {

  implicit final def ioSyntaxLogErrors[A](io: IO[A]): IOOps[A] = new IOOps(io)

  implicit final def ioRetryStrategyOps[A](io: IO[A]): IORetryStrategyOps[A] =
    new IORetryStrategyOps[A](io)

  implicit final def ioFunctorOps[A, F[_]: Functor](io: IO[F[A]]): IOFunctorOps[A, F] = new IOFunctorOps(io)
}

final class IORetryStrategyOps[A](private val io: IO[A]) extends AnyVal {

  /**
    * Apply the retry strategy on the provided IO
    */
  def retry[E <: Throwable](retryStrategy: RetryStrategy[E])(implicit E: ClassTag[E]): IO[A] =
    RetryStrategy.use(io, retryStrategy)

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

final class IOOps[A](private val io: IO[A]) extends AnyVal {
  def logErrors(action: String)(implicit logger: Log4CatsLogger[IO]): IO[A] =
    io.onError { case e => logger.warn(e)(s"Error during: '$action'") }
}
