package ch.epfl.bluebrain.nexus.delta.wiring

import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.GraphResourceEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ReferenceRegistry.LazyReferenceRegistry
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Indexing specific wiring.
  */
object StreamModule extends ModuleDef {

  make[GraphResourceStream].fromEffect {
    (
        fetchContext: FetchContext[ContextRejection],
        qc: QueryConfig,
        xas: Transactors,
        encoders: Set[GraphResourceEncoder[_, _, _]],
        rcr: RemoteContextResolution @Id("aggregate")
    ) =>
      GraphResourceStream(fetchContext, qc, xas, encoders)(rcr)
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

}
