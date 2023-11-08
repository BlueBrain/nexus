package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import cats.effect.{Clock, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import fs2.{INothing, Stream}

import scala.concurrent.duration.FiniteDuration

class BlazegraphSlowQueryDeleter(store: BlazegraphSlowQueryStore, deletionThreshold: FiniteDuration)(implicit
    clock: Clock[IO]
) {
  def deleteOldQueries: IO[Unit] = {
    IOInstant.now.flatMap { now =>
      store.removeQueriesOlderThan(now.minusMillis(deletionThreshold.toMillis))
    }
  }
}

object BlazegraphSlowQueryDeleter {
  private val projectionMetadata: ProjectionMetadata =
    ProjectionMetadata("system", "blazegraph-slow-query-log-deletion", None, None)
  def start(
      supervisor: Supervisor,
      store: BlazegraphSlowQueryStore,
      deletionThreshold: FiniteDuration,
      deletionCheckInterval: FiniteDuration
  )(implicit timer: Timer[IO]): IO[BlazegraphSlowQueryDeleter] = {
    val runner = new BlazegraphSlowQueryDeleter(store, deletionThreshold)

    val continuousStream: Stream[IO, INothing] = Stream
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
