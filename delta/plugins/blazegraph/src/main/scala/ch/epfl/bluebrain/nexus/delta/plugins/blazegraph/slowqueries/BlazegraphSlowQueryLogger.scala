package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * Logs slow queries in order to help us determine problematic queries
  */
trait BlazegraphSlowQueryLogger {

  /**
    * When a query is slow, record this with context.
    *
    * @param context
    *   information about the query which can be used in logs
    * @param query
    *   the query which should be timed, and logged if it is too slow
    * @return
    *   the query
    */
  def apply[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A]
}

object BlazegraphSlowQueryLogger {

  private val logger = Logger[BlazegraphSlowQueryLogger]

  def apply(sink: BlazegraphSlowQuerySink, longQueryThreshold: Duration)(implicit
      clock: Clock[UIO]
  ): BlazegraphSlowQueryLogger = new BlazegraphSlowQueryLogger {
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
        .tapEval(_ =>
          UIO.delay(logger.warn(s"Slow blazegraph query recorded: duration '$duration', view '${context.view}'"))
        )
        .flatMap { now =>
          sink
            .save(BlazegraphSlowQuery(context.view, context.query, isError, duration, now, context.subject))
            .onErrorHandleWith(e => UIO.delay(logger.error("error logging blazegraph slow query", e)))
        }
    }
  }
}
