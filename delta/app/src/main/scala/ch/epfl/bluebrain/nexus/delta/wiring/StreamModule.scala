package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, PurgeElemFailures}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry.LazyReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

/**
  * Indexing specific wiring.
  */
object StreamModule extends ModuleDef {

  make[GraphResourceStream].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        qc: QueryConfig,
        xas: Transactors,
        shifts: ResourceShifts
    ) =>
      GraphResourceStream(fetchContext, qc, xas, shifts)
  }

  many[PipeDef].add(DiscardMetadata)
  many[PipeDef].add(FilterDeprecated)
  many[PipeDef].add(SourceAsText)
  many[PipeDef].add(FilterByType)
  many[PipeDef].add(FilterBySchema)
  many[PipeDef].add(DataConstructQuery)
  many[PipeDef].add(SelectPredicates)
  many[PipeDef].add(DefaultLabelPredicates)

  make[ReferenceRegistry].from { (pipes: Set[PipeDef]) =>
    val registry = new ReferenceRegistry
    pipes.foreach(registry.register)
    registry
  }

  make[LazyReferenceRegistry] // to solve circular dependencies for pipes that depend on the reference registry

  make[ProjectionStore].from { (xas: Transactors, cfg: AppConfig) =>
    ProjectionStore(xas, cfg.projections.query)
  }

  make[Supervisor].fromEffect { (store: ProjectionStore, cfg: AppConfig) =>
    Supervisor(store, cfg.projections)
  }

  make[DeleteExpired].fromEffect {
    (supervisor: Supervisor, config: ProjectionConfig, xas: Transactors, clock: Clock[UIO]) =>
      DeleteExpired(supervisor, config, xas)(clock)
  }

  make[PurgeElemFailures].fromEffect {
    (supervisor: Supervisor, config: ProjectionConfig, xas: Transactors, clock: Clock[UIO]) =>
      PurgeElemFailures(supervisor, config, xas)(clock)
  }
}
