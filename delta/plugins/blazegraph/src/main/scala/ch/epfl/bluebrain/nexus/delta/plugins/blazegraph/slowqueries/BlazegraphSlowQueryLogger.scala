package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

import scala.concurrent.duration.{Duration, FiniteDuration}

trait BlazegraphSlowQueryLogger {
  def apply[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A]
}

object BlazegraphSlowQueryLogger {
  val noop: BlazegraphSlowQueryLogger = new BlazegraphSlowQueryLogger {
    override def apply[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = query
  }
  def apply(store: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
      clock: Clock[UIO]
  ): BlazegraphSlowQueryLogger = {
    new BlazegraphSlowQueryLoggerImpl(store, longQueryThreshold)
  }
}

class BlazegraphSlowQueryLoggerImpl(store: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
    clock: Clock[UIO]
) extends BlazegraphSlowQueryLogger {

  private val logger = Logger[BlazegraphSlowQueryLogger]

  def apply[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = {
    query.attempt.timed
      .flatMap { case (duration, outcome) =>
        UIO
          .when(duration >= longQueryThreshold)(logSlowQuery(context, outcome.isLeft, duration))
          .flatMap(_ => IO.fromEither(outcome))
      }
  }

  private def logSlowQuery(
      context: BlazegraphQueryContext,
      isError: Boolean,
      duration: FiniteDuration
  ): UIO[Unit] = {
    IOUtils.instant
      .tapEval(_ => UIO.delay(logger.warn(s"slow blazegraph query recorded: duration $duration, view ${context.view}")))
      .flatMap { now =>
        store
          .save(BlazegraphSlowQuery(context.view, context.query, isError, duration, now, context.subject))
          .onErrorHandleWith(e => UIO.delay(logger.error("error logging blazegraph slow query", e)))
      }
  }
}
