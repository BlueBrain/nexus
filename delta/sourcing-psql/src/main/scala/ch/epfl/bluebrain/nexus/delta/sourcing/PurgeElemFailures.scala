package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.PurgeElemFailures.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import doobie.syntax.all._
import doobie.postgres.implicits._

import java.time.Instant

final class PurgeElemFailures private[sourcing] (xas: Transactors) {

  /**
    * Deletes the projection errors that are older than the given instant.
    */
  def apply(instant: Instant): IO[Unit] =
    for {
      deleted <- sql"""
                    | DELETE FROM public.failed_elem_logs
                    | WHERE instant < $instant
                    """.stripMargin.update.run.transact(xas.write)
      _       <- IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted old indexing failures."))
    } yield ()
}

object PurgeElemFailures {

  private val logger   = Logger[PurgeElemFailures]
  private val metadata = ProjectionMetadata("system", "delete-old-failed-elem", None, None)

  /**
    * Creates a [[PurgeProjection]] to schedule in the supervisor the deletion of old projection errors.
    */
  def apply(config: PurgeConfig, xas: Transactors): PurgeProjection = {
    val purgeElemFailures = new PurgeElemFailures(xas)
    PurgeProjection(metadata, config, purgeElemFailures.apply)
  }

}
