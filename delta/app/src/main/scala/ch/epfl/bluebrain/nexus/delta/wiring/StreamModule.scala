package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.state.UniformScopedStateEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry.LazyReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{PipeDef, ProjectionStore, ReferenceRegistry, SourceDef, Supervisor}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DataConstructQuery, DefaultLabelPredicates, DiscardMetadata, FilterBySchema, FilterByType, FilterDeprecated, SelectPredicates, SourceAsText}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.sources.ScopedStateSource
import izumi.distage.model.definition.ModuleDef

object StreamModule extends ModuleDef {

  many[SourceDef].add((xas: Transactors, cfg: AppConfig, encoders: Set[UniformScopedStateEncoder[_]]) =>
    ScopedStateSource(cfg.projections.query, xas, encoders)
  )

  many[PipeDef].add(DiscardMetadata)
  many[PipeDef].add(FilterDeprecated)
  many[PipeDef].add(SourceAsText)
  many[PipeDef].add(FilterByType)
  many[PipeDef].add(FilterBySchema)
  many[PipeDef].add(DataConstructQuery)
  many[PipeDef].add(SelectPredicates)
  many[PipeDef].add(DefaultLabelPredicates)

  make[ReferenceRegistry].from { (sources: Set[SourceDef], pipes: Set[PipeDef]) =>
    val registry = new ReferenceRegistry
    sources.foreach(registry.register)
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

}
