package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.{Clock, ContextShift, IO, Sync, Timer}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceShifts
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{ProjectionErrors, Projections}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes._
import ch.epfl.bluebrain.nexus.delta.sourcing.{DeleteExpired, PurgeElemFailures, Transactors}
import izumi.distage.model.definition.ModuleDef

/**
  * Indexing specific wiring.
  */
object StreamModule extends ModuleDef {
  addImplicit[Sync[IO]]

  make[GraphResourceStream].from {
    (
        qc: QueryConfig,
        xas: Transactors,
        shifts: ResourceShifts,
        timer: Timer[IO]
    ) =>
      GraphResourceStream(qc, xas, shifts)(timer)
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

  make[Projections].from { (xas: Transactors, clock: Clock[IO], timer: Timer[IO], cfg: ProjectionConfig) =>
    Projections(xas, cfg.query, cfg.restartTtl)(clock, timer)
  }

  make[ProjectionErrors].from { (xas: Transactors, clock: Clock[IO], cfg: ProjectionConfig) =>
    ProjectionErrors(xas, cfg.query)(clock)
  }

  make[Supervisor].fromResource {
    (
        projections: Projections,
        projectionErrors: ProjectionErrors,
        timer: Timer[IO],
        cs: ContextShift[IO],
        cfg: ProjectionConfig
    ) =>
      Supervisor(projections, projectionErrors, cfg)(timer, cs)
  }

  make[DeleteExpired].fromEffect {
    (supervisor: Supervisor, config: ProjectionConfig, xas: Transactors, clock: Clock[IO], timer: Timer[IO]) =>
      DeleteExpired(supervisor, config, xas)(clock, timer)
  }

  make[PurgeElemFailures].fromEffect {
    (supervisor: Supervisor, config: ProjectionConfig, xas: Transactors, clock: Clock[IO], timer: Timer[IO]) =>
      PurgeElemFailures(supervisor, config, xas)(clock, timer)
  }
}
