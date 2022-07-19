package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import monix.bio.{IO, Task, UIO}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

/**
  * An fs2.Pipe for projections that observes the elements processed by the stream and passivates the stream after a
  * defined interval of inactivity.
  */
object Passivation {

  /**
    * Constructs an fs2.Pipe that observes the elements processed by the stream and at regular intervals decides if the
    * stream becomes idle. When a stream is considered idle, the stream is gracefully stopped.
    * @param inactiveInterval
    *   the interval after which when no additional elements are observed the stream is considered idle
    * @param checkInterval
    *   how frequently to check if a stream becomes idle
    * @param status
    *   a reference to the execution status of the projection
    * @param stopFn
    *   a fn that stops the stream
    */
  def apply[A](
      inactiveInterval: FiniteDuration,
      checkInterval: FiniteDuration,
      status: Ref[Task, ExecutionStatus],
      stopFn: () => Task[Unit]
  ): Pipe[Task, Elem[A], Elem[A]] =
    stream =>
      Stream
        .eval(createRef)
        .flatMap { ref =>
          stream
            .evalTap(_ => updateRefTs(ref))
            .concurrently(checkRefStream(ref, status, stopFn, inactiveInterval, checkInterval))
        }

  private def nowInMillis: UIO[Long] =
    IO.clock[Nothing].realTime(TimeUnit.MILLISECONDS)

  private def createRef: Task[Ref[Task, Long]] =
    nowInMillis.flatMap(ts => Ref.of(ts))

  private def updateRefTs(ref: Ref[Task, Long]): IO[Throwable, Unit] =
    for {
      ts <- nowInMillis
      _  <- ref.set(ts)
    } yield ()

  private def checkRefStream(
      ref: Ref[Task, Long],
      status: Ref[Task, ExecutionStatus],
      stopFn: () => Task[Unit],
      inactiveInterval: FiniteDuration,
      checkInterval: FiniteDuration
  ): Stream[Task, FiniteDuration] =
    Stream.awakeEvery[Task](checkInterval).evalTap { _ =>
      for {
        lastElemTs <- ref.get
        currentTs  <- nowInMillis
        shouldStop  = currentTs - lastElemTs > inactiveInterval.toMillis
        _          <- if (shouldStop) status.update(_.passivated) >> stopFn()
                      else Task.unit
      } yield ()
    }

}
