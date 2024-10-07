package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectLastUpdateStore
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{SelectFilter, StreamingQuery}

trait ProjectLastUpdateProjection

// $COVERAGE-OFF$
object ProjectLastUpdateProjection {

  private val projectionMetadata: ProjectionMetadata = ProjectionMetadata("system", "project-last-updates", None, None)

  // We need a value to return to Distage
  private val dummy = new ProjectLastUpdateProjection {}

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
    *   a batch configuration for the sink
    * @param queryConfig
    *   query config for fetching scoped states
    */
  def apply(
      supervisor: Supervisor,
      store: ProjectLastUpdateStore,
      xas: Transactors,
      batchConfig: BatchConfig,
      queryConfig: QueryConfig
  ): IO[ProjectLastUpdateProjection] = {
    val elemStream = (offset: Offset) => StreamingQuery.elems(Scope.root, offset, SelectFilter.latest, queryConfig, xas)
    apply(supervisor, store, elemStream, batchConfig)
  }

  def apply(
      supervisor: Supervisor,
      store: ProjectLastUpdateStore,
      elemStream: Offset => ElemStream[Unit],
      batchConfig: BatchConfig
  ): IO[ProjectLastUpdateProjection] = {
    val source = Source { (offset: Offset) => elemStream(offset) }

    for {
      sink              <- ProjectLastUpdatesSink(store, batchConfig.maxElements, batchConfig.maxInterval)
      compiledProjection = CompiledProjection.compile(
                             projectionMetadata,
                             ExecutionStrategy.PersistentSingleNode,
                             source,
                             sink
                           )
      projection        <- IO.fromEither(compiledProjection)
      _                 <- supervisor.run(projection)
    } yield dummy
  }

}
// $COVERAGE-ON$
