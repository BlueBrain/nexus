package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

class SparqlSlowQueryDeleter(store: SparqlSlowQueryStore, deletionThreshold: FiniteDuration, clock: Clock[IO]) {
  def deleteOldQueries: IO[Unit] = {
    clock.realTimeInstant.flatMap { now =>
      store.removeQueriesOlderThan(now.minusMillis(deletionThreshold.toMillis))
    }
  }
}

object SparqlSlowQueryDeleter {
  private val projectionMetadata: ProjectionMetadata =
    ProjectionMetadata("system", "sparql-slow-query-log-deletion", None, None)
  def start(
      supervisor: Supervisor,
      store: SparqlSlowQueryStore,
      deletionThreshold: FiniteDuration,
      deletionCheckInterval: FiniteDuration,
      clock: Clock[IO]
  ): IO[SparqlSlowQueryDeleter] = {
    val runner = new SparqlSlowQueryDeleter(store, deletionThreshold, clock)

    val continuousStream: Stream[IO, Nothing] = Stream
      .fixedRate[IO](deletionCheckInterval)
      .evalMap(_ => runner.deleteOldQueries)
      .drain

    val compiledProjection =
      CompiledProjection.fromStream(projectionMetadata, ExecutionStrategy.TransientSingleNode, _ => continuousStream)

    supervisor
      .run(compiledProjection)
      .map(_ => runner)
  }
}
