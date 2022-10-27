package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.DeleteExpired.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import monix.bio.{Task, UIO}

/**
  * Allow to delete expired ephemeral states
  */
final class DeleteExpired private[sourcing] (xas: Transactors)(implicit clock: Clock[UIO]) {

  def apply(): UIO[Unit] = {
    for {
      instant <- IOUtils.instant
      deleted <- sql"""
                  | DELETE FROM public.ephemeral_states
                  | WHERE expires < $instant
                  """.stripMargin.update.run.transact(xas.write).hideErrors
      _       <- UIO.when(deleted > 0)(UIO.delay(logger.info(s"Deleted $deleted expired ephemeral states")))
    } yield ()
  }
}

object DeleteExpired {
  private val logger: Logger = Logger[DeleteExpired]

  private val metadata: ProjectionMetadata = ProjectionMetadata("system", "delete-expired", None, None)

  /**
    * Creates a [[DeleteExpired]] instance and schedules in the supervisor the deletion of expired ephemeral states
    */
  def apply(supervisor: Supervisor, config: ProjectionConfig, xas: Transactors)(implicit
      clock: Clock[UIO]
  ): Task[DeleteExpired] = {
    val deleteExpired = new DeleteExpired(xas)

    val stream = Stream
      .awakeEvery[Task](config.deleteExpiredEvery)
      .evalTap(_ => deleteExpired())
      .drain

    supervisor
      .run(
        CompiledProjection.fromStream(
          metadata,
          ExecutionStrategy.TransientSingleNode,
          _ => stream
        )
      )
      .as(deleteExpired)
  }
}
