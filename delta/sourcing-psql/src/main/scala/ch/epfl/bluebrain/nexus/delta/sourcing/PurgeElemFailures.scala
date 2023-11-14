package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.PurgeElemFailures.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream

import scala.concurrent.duration._

final class PurgeElemFailures private[sourcing] (xas: Transactors, ttl: FiniteDuration)(implicit clock: Clock[IO]) {

  /**
    * Deletes the projection errors that are older than the given `ttl`.
    */
  def apply(): IO[Unit] =
    for {
      threshold <- clock.realTimeInstant.map(_.minusMillis(ttl.toMillis))
      deleted   <- sql"""
                    | DELETE FROM public.failed_elem_logs
                    | WHERE instant < $threshold
                    """.stripMargin.update.run.transact(xas.write)
      _         <- IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted old indexing failures."))
    } yield ()
}

object PurgeElemFailures {

  private val logger   = Logger[PurgeElemFailures]
  private val metadata = ProjectionMetadata("system", "delete-old-failed-elem", None, None)

  /**
    * Creates a [[PurgeElemFailures]] instance and schedules in the supervisor the deletion of old projection errors.
    */
  def apply(supervisor: Supervisor, config: ProjectionConfig, xas: Transactors): IO[PurgeElemFailures] = {
    val purgeElemFailures = new PurgeElemFailures(xas, config.failedElemTtl)

    val stream = Stream
      .awakeEvery[IO](config.deleteExpiredEvery)
      .evalTap(_ => purgeElemFailures())
      .drain

    supervisor
      .run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => stream))
      .as(purgeElemFailures)
  }

}
