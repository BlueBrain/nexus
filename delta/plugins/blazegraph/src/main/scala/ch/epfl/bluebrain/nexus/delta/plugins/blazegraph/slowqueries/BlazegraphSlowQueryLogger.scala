package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery.BlazegraphQueryContext
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.BlazegraphSlowQuery

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
  def apply[A](context: BlazegraphQueryContext, query: IO[A]): IO[A]
}

object BlazegraphSlowQueryLogger {

  private val logger = Logger[BlazegraphSlowQueryLogger]

  def apply(sink: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
      clock: Clock[IO]
  ): BlazegraphSlowQueryLogger = new BlazegraphSlowQueryLogger {
    def apply[A](context: BlazegraphQueryContext, query: IO[A]): IO[A] = {
      IOInstant
        .timed(query.attempt)
        .flatMap { case (outcome, duration) =>
          IO
            .whenA(duration >= longQueryThreshold)(logSlowQuery(context, outcome.isLeft, duration))
            .flatMap(_ => IO.fromEither(outcome))
        }
    }

    private def logSlowQuery(
        context: BlazegraphQueryContext,
        isError: Boolean,
        duration: FiniteDuration
    ): IO[Unit] = {
      IOInstant.now
        .flatTap(_ =>
          IO.delay(logger.warn(s"Slow blazegraph query recorded: duration '$duration', view '${context.view}'"))
        )
        .flatMap { now =>
          sink
            .save(BlazegraphSlowQuery(context.view, context.query, isError, duration, now, context.subject))
            .handleErrorWith(e => logger.error(e)("error logging blazegraph slow query"))
        }
    }
  }
}
