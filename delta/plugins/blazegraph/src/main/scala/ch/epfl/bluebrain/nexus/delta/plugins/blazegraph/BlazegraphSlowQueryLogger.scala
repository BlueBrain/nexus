package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

import scala.concurrent.duration.{Duration, FiniteDuration}

trait BlazegraphSlowQueryLogger  {
  def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A]
}
object BlazegraphSlowQueryLogger {
  def noop: BlazegraphSlowQueryLogger = new NoopBlazegraphSlowQueryLogger
}

class NoopBlazegraphSlowQueryLogger extends BlazegraphSlowQueryLogger {
  override def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = query
}
class BlazegraphSlowQueryLoggerImpl(store: BlazegraphSlowQueryStore, longQueryThreshold: Duration)(implicit
    clock: Clock[UIO]
)                                   extends BlazegraphSlowQueryLogger {

  private val logger = Logger[BlazegraphSlowQueryLogger]
  def logSlowQueries[E, A](context: BlazegraphQueryContext, query: IO[E, A]): IO[E, A] = {
    query.timed
      .flatMap { case (duration, r) =>
        UIO
          .when(duration >= longQueryThreshold)(logSlowQuery(context, duration))
          .map(_ => r)
      }
  }

  private def logSlowQuery(context: BlazegraphQueryContext, duration: FiniteDuration): UIO[Unit] = {
    IOUtils.instant.flatMap { now =>
      store
        .save(BlazegraphSlowQuery(context.viewId, context.project, context.query, duration, now, context.subject))
        .redeemWith[Nothing, Unit](e => UIO.delay(logger.error("error logging blazegraph slow query", e)), UIO.pure)
    }

  }
}
