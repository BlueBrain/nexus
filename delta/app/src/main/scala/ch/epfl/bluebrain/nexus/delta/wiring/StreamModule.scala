package ch.epfl.bluebrain.nexus.delta.wiring

import cats.data.NonEmptyList
import cats.effect.{Clock, IO, Sync}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ElemQueryConfig, ProjectLastUpdateConfig, ProjectionConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectLastUpdateStore, ProjectLastUpdateStream, ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.ElemStreaming
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import ch.epfl.bluebrain.nexus.delta.sourcing.tombstone.TombstoneStore
import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, PurgeElemFailures, Transactors}
import izumi.distage.model.definition.ModuleDef

/**
  * Indexing specific wiring.
  */
object StreamModule extends ModuleDef {
  addImplicit[Sync[IO]]

  make[ElemStreaming].from {
    (xas: Transactors, shifts: ResourceShifts, queryConfig: ElemQueryConfig, activitySignals: ProjectActivitySignals) =>
      new ElemStreaming(xas, NonEmptyList.fromList(shifts.entityTypes.toList), queryConfig, activitySignals)
  }

  make[GraphResourceStream].from { (elemStreaming: ElemStreaming, shifts: ResourceShifts) =>
    GraphResourceStream(elemStreaming, shifts)
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

  make[Projections].from { (xas: Transactors, cfg: ProjectionConfig, clock: Clock[IO]) =>
    Projections(xas, cfg.query, clock)
  }

  make[ProjectionErrors].from { (xas: Transactors, clock: Clock[IO], cfg: ProjectionConfig) =>
    ProjectionErrors(xas, cfg.query, clock)
  }

  make[Supervisor].fromResource {
    (
        projections: Projections,
        projectionErrors: ProjectionErrors,
        cfg: ProjectionConfig
    ) => Supervisor(projections, projectionErrors, cfg)
  }

  make[ProjectLastUpdateStore].from { (xas: Transactors) => ProjectLastUpdateStore(xas) }
  make[ProjectLastUpdateStream].from { (xas: Transactors, config: ProjectLastUpdateConfig) =>
    ProjectLastUpdateStream(xas, config.query)
  }

  make[ProjectLastUpdateWrites].fromEffect {
    (supervisor: Supervisor, store: ProjectLastUpdateStore, xas: Transactors, config: ProjectLastUpdateConfig) =>
      ProjectLastUpdateWrites(supervisor, store, xas, config.batch)
  }

  make[ProjectActivitySignals].fromEffect {
    (supervisor: Supervisor, stream: ProjectLastUpdateStream, clock: Clock[IO], config: ProjectLastUpdateConfig) =>
      ProjectActivitySignals(supervisor, stream, clock, config.inactiveInterval)
  }

  make[PurgeProjectionCoordinator.type].fromEffect {
    (supervisor: Supervisor, clock: Clock[IO], projections: Set[PurgeProjection]) =>
      PurgeProjectionCoordinator(supervisor, clock, projections)
  }

  many[PurgeProjection].add { (config: ProjectionConfig, xas: Transactors) =>
    DeleteExpired(config.deleteExpiredEvery, xas)
  }

  many[PurgeProjection].add { (config: ProjectionConfig, xas: Transactors) =>
    PurgeElemFailures(config.failedElemPurge, xas)
  }

  many[PurgeProjection].add { (config: ProjectionConfig, xas: Transactors) =>
    TombstoneStore.deleteExpired(config.tombstonePurge, xas)
  }
}
