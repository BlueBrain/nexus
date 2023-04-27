package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries

import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import fs2.Stream
import monix.bio.Task

import scala.concurrent.duration.FiniteDuration

class BlazegraphSlowQueryDeleter(store: BlazegraphSlowQueryStore, deletionThreshold: FiniteDuration) {
  def deleteOldQueries: Task[Unit] = {
    IOUtils.instant.flatMap { now =>
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
  ): Task[BlazegraphSlowQueryDeleter] = {
    val runner = new BlazegraphSlowQueryDeleter(store, deletionThreshold)

    val continuousStream = Stream
      .fixedRate[Task](deletionCheckInterval)
      .evalMap(_ => runner.deleteOldQueries)
      .drain

    val compiledProjection =
      CompiledProjection.fromStream(projectionMetadata, ExecutionStrategy.TransientSingleNode, _ => continuousStream)

    supervisor
      .run(compiledProjection)
      .map(_ => runner)
  }
}
