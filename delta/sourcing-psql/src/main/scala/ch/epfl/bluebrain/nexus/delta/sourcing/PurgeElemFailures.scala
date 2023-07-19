package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.PurgeElemFailures.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

final class PurgeElemFailures private[sourcing] (xas: Transactors, ttl: FiniteDuration)(implicit clock: Clock[UIO]) {

  /**
    * Deletes the projection errors that are older than the given `ttl`.
    */
  def apply(): UIO[Unit] =
    for {
      threshold <- IOUtils.instant.map(_.minusMillis(ttl.toMillis))
      deleted   <- sql"""
                    | DELETE FROM public.failed_elem_logs
                    | WHERE instant < $threshold
                    """.stripMargin.update.run.transact(xas.write).hideErrors
      _         <- UIO.when(deleted > 0)(UIO.delay(logger.info(s"Deleted {} old indexing failures.", deleted)))
    } yield ()
}

object PurgeElemFailures {

  private val logger   = Logger[PurgeElemFailures]
  private val metadata = ProjectionMetadata("system", "delete-old-failed-elem", None, None)

  /**
    * Creates a [[PurgeElemFailures]] instance and schedules in the supervisor the deletion of old projection errors.
    */
  def apply(supervisor: Supervisor, config: ProjectionConfig, xas: Transactors)(implicit
      clock: Clock[UIO]
  ): Task[PurgeElemFailures] = {
    val purgeElemFailures = new PurgeElemFailures(xas, config.failedElemTtl)

    val stream = Stream
      .awakeEvery[Task](config.deleteExpiredEvery)
      .evalTap(_ => purgeElemFailures())
      .drain

    supervisor
      .run(CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => stream))
      .as(purgeElemFailures)
  }

}
