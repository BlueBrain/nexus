package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore.logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ProjectionConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Store to handle composite views restarts
  */
final class CompositeRestartStore(xas: Transactors) {

  /**
    * Save a composite restart
    */
  def save(restart: CompositeRestart): UIO[Unit] =
    sql"""INSERT INTO public.composite_restarts (project, id, value, instant, acknowledged)
         |VALUES (${restart.project}, ${restart.id}, ${restart.asJson} ,${restart.instant}, false)
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
      .hideErrors

  /**
    * Acknowledge a composite restart
    */
  def acknowledge(offset: Offset): UIO[Unit] =
    sql"""UPDATE public.composite_restarts SET acknowledged = true
         |WHERE ordering = ${offset.value}
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
      .hideErrors

  /**
    * Delete expired composite restarts
    */
  def deleteExpired(instant: Instant): UIO[Unit] =
    sql"""DELETE FROM public.composite_restarts WHERE instant < $instant""".update.run
      .transact(xas.write)
      .hideErrors
      .tapEval { deleted =>
        UIO.when(deleted > 0)(UIO.delay(logger.info(s"Deleted $deleted composite restarts.")))
      }
      .void

  /**
    * Get the first non-processed restart for a composite view
    * @param view
    *   the view reference
    */
  def head(view: ViewRef): UIO[Option[Elem[CompositeRestart]]] =
    fetchOne(view, asc = true)

  /**
    * Get the last non-processed restart for a composite view
    * @param view
    *   the view reference
    */
  def last(view: ViewRef): UIO[Option[Elem[CompositeRestart]]] =
    fetchOne(view, asc = false)

  private def fetchOne(view: ViewRef, asc: Boolean) = {
    val direction = if (asc) fr"ASC" else fr"DESC"
    sql"""SELECT ordering, project, id, value, instant from public.composite_restarts
         |WHERE project = ${view.project} and id = ${view.viewId} and acknowledged = false
         |ORDER BY ordering $direction
         |LIMIT 1""".stripMargin
      .query[(Offset, ProjectRef, Iri, Json, Instant)]
      .map { case (offset, project, id, json, instant) =>
        Elem.fromEither(entityType, id, Some(project), instant, offset, json.as[CompositeRestart], 1)
      }
      .option
      .transact(xas.read)
      .hideErrors
  }

}

object CompositeRestartStore {
  private val logger: Logger = Logger[CompositeRestartStore]

  private val purgeCompositeRestartMetadata = ProjectionMetadata("composite-views", "purge-composite-restarts")

  /**
    * Register the task to delete expired restarts in the supervisor
    * @param store
    *   the store
    * @param supervisor
    *   the supervisor
    * @param config
    *   the projection config
    */
  def deleteExpired(store: CompositeRestartStore, supervisor: Supervisor, config: ProjectionConfig)(implicit
      clock: Clock[UIO]
  ): Task[Unit] = {
    val deleteExpiredRestarts =
      IOUtils.instant.flatMap { now =>
        store.deleteExpired(now.minusMillis(config.restartTtl.toMillis))
      }
    supervisor
      .run(
        CompiledProjection.fromStream(
          purgeCompositeRestartMetadata,
          ExecutionStrategy.TransientSingleNode,
          _ =>
            Stream
              .awakeEvery[Task](config.deleteExpiredEvery)
              .evalTap(_ => deleteExpiredRestarts)
              .drain
        )
      )
      .void
  }
}
