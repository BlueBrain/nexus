package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.syntax.all.*
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import fs2.Stream
import java.time.Instant

sealed trait PurgeProjectionCoordinator

object PurgeProjectionCoordinator extends PurgeProjectionCoordinator {

  final case class PurgeProjection(metadata: ProjectionMetadata, config: PurgeConfig, task: Instant => IO[Unit])

  def apply(
      supervisor: Supervisor,
      clock: Clock[IO],
      purgeProjections: Set[PurgeProjection]
  ): IO[PurgeProjectionCoordinator.type] =
    purgeProjections.toList
      .traverse { projection =>
        val config             = projection.config
        def purgeForInstant    =
          clock.realTimeInstant.flatMap { now => projection.task(now.minusMillis(config.ttl.toMillis)) }
        val compiledProjection = CompiledProjection.fromStream(
          projection.metadata,
          ExecutionStrategy.TransientSingleNode,
          _ =>
            Stream
              .awakeEvery[IO](config.deleteExpiredEvery)
              .evalTap(_ => purgeForInstant)
              .drain
        )
        supervisor.run(compiledProjection)
      }
      .as(PurgeProjectionCoordinator)
}
