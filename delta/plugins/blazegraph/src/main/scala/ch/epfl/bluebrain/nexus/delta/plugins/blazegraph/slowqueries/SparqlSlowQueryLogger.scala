package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.model.SparqlSlowQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

import scala.concurrent.duration.{Duration, FiniteDuration}

/**
  * Logs slow queries in order to help us determine problematic queries
  */
trait SparqlSlowQueryLogger {

  def apply[A](view: ViewRef, sparql: SparqlQuery, subject: Subject, io: IO[A]): IO[A]
}

object SparqlSlowQueryLogger {

  private val logger = Logger[SparqlSlowQueryLogger]

  def noop: SparqlSlowQueryLogger = new SparqlSlowQueryLogger {
    override def apply[A](view: ViewRef, sparql: SparqlQuery, subject: Subject, io: IO[A]): IO[A] = io
  }

  def apply(sink: SparqlSlowQueryStore, longQueryThreshold: Duration, clock: Clock[IO]): SparqlSlowQueryLogger = {

    new SparqlSlowQueryLogger {
      def apply[A](view: ViewRef, sparql: SparqlQuery, subject: Subject, io: IO[A]): IO[A] = {

        def logSlowQuery(isError: Boolean, duration: FiniteDuration) =
          logger.warn(s"Slow sparql query recorded: duration '$duration', view '$view'") >>
            clock.realTimeInstant
              .flatMap { now =>
                sink
                  .save(SparqlSlowQuery(view, sparql, isError, duration, now, subject))
                  .handleErrorWith(e => logger.error(e)("Error logging sparql slow query"))
              }

        for {
          (duration, outcome) <- io.attempt.timed
          _                   <- IO.whenA(duration >= longQueryThreshold)(logSlowQuery(outcome.isLeft, duration))
          result              <- IO.fromEither(outcome)
        } yield result
      }
    }
  }
}
