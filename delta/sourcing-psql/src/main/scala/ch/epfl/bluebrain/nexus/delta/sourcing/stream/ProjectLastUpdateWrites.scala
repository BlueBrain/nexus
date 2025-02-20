package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectLastUpdateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{ElemStreaming, SelectFilter}
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}

trait ProjectLastUpdateWrites

// $COVERAGE-OFF$
object ProjectLastUpdateWrites {

  private val projectionMetadata: ProjectionMetadata =
    ProjectionMetadata("system", "project-last-updates-writes", None, None)

  // We need a value to return to Distage
  private val dummy = new ProjectLastUpdateWrites {}

  /**
    * Creates a projection allowing to compute the last instant and the last ordering values for every project.
    *
    * This value is then used for passivation and the reactivation of other projections (ex: those related to indexing)
    *
    * @param supervisor
    *   the supervisor which will supervise the projection
    * @param store
    *   the store allowing to fetch and save project last updates
    * @param xas
    *   doobie
    * @param batchConfig
    *   a batch configuration for fetching the elems and for the sink
    */
  def apply(
      supervisor: Supervisor,
      store: ProjectLastUpdateStore,
      xas: Transactors,
      batchConfig: BatchConfig
  ): IO[ProjectLastUpdateWrites] = {
    // We build an elem streaming based on a delay
    val es         = ElemStreaming.delay(xas, None, batchConfig.maxElements, batchConfig.maxInterval)
    val elemStream = (offset: Offset) => es(Scope.root, offset, SelectFilter.latest)
    apply(supervisor, store, elemStream, batchConfig)
  }

  def apply(
      supervisor: Supervisor,
      store: ProjectLastUpdateStore,
      elemStream: Offset => ElemStream[Unit],
      batchConfig: BatchConfig
  ): IO[ProjectLastUpdateWrites] = {
    val source             = Source { (offset: Offset) => elemStream(offset) }
    val sink               = ProjectLastUpdatesSink(store, batchConfig.maxElements, batchConfig.maxInterval)
    val compiledProjection = CompiledProjection.compile(
      projectionMetadata,
      ExecutionStrategy.PersistentSingleNode,
      source,
      sink
    )

    IO.fromEither(compiledProjection)
      .flatMap { projection =>
        supervisor.run(projection)
      }
      .as(dummy)
  }

}
// $COVERAGE-ON$
