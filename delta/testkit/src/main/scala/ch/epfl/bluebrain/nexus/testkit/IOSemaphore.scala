package ch.epfl.bluebrain.nexus.testkit

import cats.effect.concurrent.Semaphore
import monix.bio.{IO, Task, UIO}

// $COVERAGE-OFF$
/**
  * A bifunctor semaphore fixed in IO.
  */
trait IOSemaphore {

  /**
    * Returns the number of permits currently available. Always non-negative.
    *
    * May be out of date the instant after it is retrieved.
    * Use `[[tryAcquire]]` or `[[tryAcquireN]]` if you wish to attempt an
    * acquire, returning immediately if the current count is not high enough
    * to satisfy the request.
    */
  def available: UIO[Long]

  /**
    * Obtains a snapshot of the current count. May be negative.
    *
    * Like [[available]] when permits are available but returns the number of permits
    * callers are waiting for when there are no permits available.
    */
  def count: UIO[Long]

  /**
    * Acquires `n` permits.
    *
    * The returned effect semantically blocks until all requested permits are
    * available. Note that acquires are statisfied in strict FIFO order, so given
    * `s: Semaphore[F]` with 2 permits available, an `acquireN(3)` will
    * always be satisfied before a later call to `acquireN(1)`.
    *
    * @param n number of permits to acquire - must be >= 0
    */
  def acquireN(n: Long): UIO[Unit]

  /** Acquires a single permit. Alias for `[[acquireN]](1)`. */
  def acquire: UIO[Unit] = acquireN(1)

  /**
    * Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n < 0`.
    *
    * @param n number of permits to acquire - must be >= 0
    */
  def tryAcquireN(n: Long): UIO[Boolean]

  /** Alias for `[[tryAcquireN]](1)`. */
  def tryAcquire: UIO[Boolean] = tryAcquireN(1)

  /**
    * Releases `n` permits, potentially unblocking up to `n` outstanding acquires.
    *
    * @param n number of permits to release - must be >= 0
    */
  def releaseN(n: Long): UIO[Unit]

  /** Releases a single permit. Alias for `[[releaseN]](1)`. */
  def release: UIO[Unit] = releaseN(1)

  /**
    * Returns an effect that acquires a permit, runs the supplied effect, and then releases the permit.
    */
  def withPermit[E, A](t: IO[E, A]): IO[E, A]

}

object IOSemaphore {

  /**
    * Creates a new `Semaphore`, initialized with `n` available permits.
    *
    * @see [[Semaphore.apply]]
    */
  final def apply(n: Long): UIO[IOSemaphore] =
    Semaphore[Task](n).hideErrors.map(fromTask)

  /**
    * Lifts a [[Semaphore]] into an [[IOSemaphore]].
    */
  final def fromTask(semaphore: Semaphore[Task]): IOSemaphore =
    new IOSemaphore {
      override def available: UIO[Long]                    = semaphore.available.hideErrors
      override def count: UIO[Long]                        = semaphore.count.hideErrors
      override def acquireN(n: Long): UIO[Unit]            = semaphore.acquireN(n).hideErrors
      override def tryAcquireN(n: Long): UIO[Boolean]      = semaphore.tryAcquireN(n).hideErrors
      override def releaseN(n: Long): UIO[Unit]            = semaphore.releaseN(n).hideErrors
      override def withPermit[E, A](t: IO[E, A]): IO[E, A] =
        semaphore.withPermit(t.attempt).hideErrors.flatMap(IO.fromEither)
    }
}
// $COVERAGE-ON$
