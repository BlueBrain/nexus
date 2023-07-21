package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ProjectContextCache
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, PurgeElemFailures, Transactors}
import izumi.distage.model.definition.ModuleDef
import monix.bio.{Task, UIO}

/**
  * Indexing specific wiring.
  */
object StreamModule extends ModuleDef {
  addImplicit[Sync[Task]]

  make[GraphResourceStream].from {
    (
        contextCache: ProjectContextCache,
        qc: QueryConfig,
        xas: Transactors,
        shifts: ResourceShifts
    ) =>
      GraphResourceStream(contextCache.fetchContext, qc, xas, shifts)
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

  make[Projections].from { (xas: Transactors, cfg: ProjectionConfig) =>
    Projections(xas, cfg.query, cfg.restartTtl)
  }

  make[ProjectionErrors].from { (xas: Transactors, cfg: ProjectionConfig) =>
    ProjectionErrors(xas, cfg.query)
  }

  make[Supervisor].fromResource {
    (projections: Projections, projectionErrors: ProjectionErrors, cfg: ProjectionConfig) =>
      Supervisor(projections, projectionErrors, cfg)
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
