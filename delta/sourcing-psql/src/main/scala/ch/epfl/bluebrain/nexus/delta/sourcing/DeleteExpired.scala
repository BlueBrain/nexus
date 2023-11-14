package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.DeleteExpired.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, ExecutionStrategy, ProjectionMetadata, Supervisor}
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream

/**
  * Allow to delete expired ephemeral states
  */
final class DeleteExpired private[sourcing] (xas: Transactors)(implicit clock: Clock[IO]) {

  def apply(): IO[Unit] = {
    for {
      instant <- IOInstant.now
      deleted <- sql"""
                  | DELETE FROM public.ephemeral_states
                  | WHERE expires < $instant
                  """.stripMargin.update.run.transact(xas.write)
      _       <- IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted expired ephemeral states"))
    } yield ()
  }
}

object DeleteExpired {
  private val logger = Logger[DeleteExpired]

  private val metadata: ProjectionMetadata = ProjectionMetadata("system", "delete-expired", None, None)

  /**
    * Creates a [[DeleteExpired]] instance and schedules in the supervisor the deletion of expired ephemeral states
    */
  def apply(supervisor: Supervisor, config: ProjectionConfig, xas: Transactors)(implicit
      clock: Clock[IO]
  ): IO[DeleteExpired] = {
    val deleteExpired = new DeleteExpired(xas)

    val stream = Stream
      .awakeEvery[IO](config.deleteExpiredEvery)
      .evalTap(_ => deleteExpired())
      .drain

    val deleteExpiredProjection =
      CompiledProjection.fromStream(metadata, ExecutionStrategy.TransientSingleNode, _ => stream)
    supervisor.run(deleteExpiredProjection).as(deleteExpired)
  }
}
