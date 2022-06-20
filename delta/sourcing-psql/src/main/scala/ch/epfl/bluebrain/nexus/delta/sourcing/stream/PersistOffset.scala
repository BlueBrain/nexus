package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.concurrent.Ref
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}

import scala.concurrent.duration.FiniteDuration

/**
  * An fs2.Pipe for projections that observes and stores the ProjectionOffset at regular intervals.
  */
object PersistOffset {

  /**
    * Constructs an fs2.Pipe that observes and stores the ProjectionOffset at regular intervals given a persist
    * function. It runs a separate stream concurrently that is awoken at the specified interval; for every tick the last
    * written offset is compared to the last observed offset. If there are any differences, the provided
    * `persistOffsetFn` is called with the new offset.
    *
    * @param initial
    *   the initial offset (last written and last observed)
    * @param interval
    *   the frequency for checking/storing the observed offset.
    * @param persistOffsetFn
    *   a fn that persists a provided offset
    * @tparam A
    *   the underlying element value
    */
  def apply[A](
      initial: ProjectionOffset,
      interval: FiniteDuration,
      persistOffsetFn: ProjectionOffset => Task[Unit]
  ): Pipe[Task, Elem[A], Elem[A]] = { stream =>
    for {
      lastObservedRef <- Stream.eval(Ref.of[Task, ProjectionOffset](initial))
      lastWrittenRef  <- Stream.eval(Ref.of[Task, ProjectionOffset](initial))
      _               <- Stream.eval(lastObservedRef.get.flatMap(persistOffsetFn)) // write at least once at the beginning
      both            <- stream
                           .evalTap(elem => lastObservedRef.update(_.add(elem.ctx, elem.offset)))
                           .concurrently(
                             persistOffsetStream(
                               persistOffsetFn,
                               interval,
                               lastObservedRef,
                               lastWrittenRef
                             )
                           )
    } yield both
  }

  private def persistOffsetStream(
      persistOffsetFn: ProjectionOffset => Task[Unit],
      interval: FiniteDuration,
      lastObservedRef: Ref[Task, ProjectionOffset],
      lastWrittenRef: Ref[Task, ProjectionOffset]
  ): Stream[Task, FiniteDuration] =
    Stream.awakeEvery[Task](interval).evalTap { _ =>
      for {
        lastObserved <- lastObservedRef.get
        lastWritten  <- lastWrittenRef.get
        _            <- if (lastObserved != lastWritten)
                          persistOffsetFn.apply(lastObserved) >> lastWrittenRef.set(lastObserved)
                        else UIO.unit
      } yield ()
    }
}
