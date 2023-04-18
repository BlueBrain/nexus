package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphQueryContext
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

import scala.concurrent.duration.{Duration, FiniteDuration}

trait BlazegraphSlowQueryLogger {
  def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A]
}

object BlazegraphSlowQueryLogger {
  def noop: BlazegraphSlowQueryLogger = new BlazegraphSlowQueryLogger {
    override def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = query
  }
  def store(store: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
      clock: Clock[UIO]
  ): BlazegraphSlowQueryLogger = {
    new BlazegraphSlowQueryLoggerImpl(store, longQueryThreshold)
  }
}

class BlazegraphSlowQueryLoggerImpl(store: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
    clock: Clock[UIO]
) extends BlazegraphSlowQueryLogger {

  private val logger = Logger[BlazegraphSlowQueryLogger]

  def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = {
    query.attempt.timed
      .flatMap { case (duration, outcome) =>
        UIO
          .when(duration >= longQueryThreshold)(logSlowQuery(context, duration))
          .flatMap(_ => IO.fromEither(outcome))
      }
  }

  private def logSlowQuery(context: BlazegraphQueryContext, duration: FiniteDuration): UIO[Unit] = {
    IOUtils.instant.flatMap { now =>
      store
        .save(BlazegraphSlowQuery(context.view, context.query, duration, now, context.subject))
        .onErrorHandleWith(e => UIO.delay(logger.error("error logging blazegraph slow query", e)))
    }
  }
}
