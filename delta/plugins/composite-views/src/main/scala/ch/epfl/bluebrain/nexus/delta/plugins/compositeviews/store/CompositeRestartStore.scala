package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore.logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.PurgeConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*
import doobie.syntax.all.*
import doobie.postgres.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.PurgeProjectionCoordinator.PurgeProjection
import io.circe.Json
import io.circe.syntax.EncoderOps

import java.time.Instant

/**
  * Store to handle composite views restarts
  */
final class CompositeRestartStore(xas: Transactors) {

  /**
    * Save a composite restart
    */
  def save(restart: CompositeRestart): IO[Unit] =
    sql"""INSERT INTO public.composite_restarts (project, id, value, instant, acknowledged)
         |VALUES (${restart.view.project}, ${restart.view.viewId}, ${restart.asJson} ,${restart.instant}, false)
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

  /**
    * Acknowledge a composite restart
    */
  def acknowledge(offset: Offset): IO[Unit] =
    sql"""UPDATE public.composite_restarts SET acknowledged = true
         |WHERE ordering = ${offset.value}
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

  /**
    * Delete expired composite restarts
    */
  def deleteExpired(instant: Instant): IO[Unit] =
    sql"""DELETE FROM public.composite_restarts WHERE instant < $instant""".update.run
      .transact(xas.write)
      .flatTap { deleted =>
        IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted composite restarts."))
      }
      .void

  /**
    * Get the first non-processed restart for a composite view
    * @param view
    *   the view reference
    */
  def head(view: ViewRef): IO[Option[Elem[CompositeRestart]]] =
    fetchOne(view, asc = true)

  /**
    * Get the last non-processed restart for a composite view
    * @param view
    *   the view reference
    */
  def last(view: ViewRef): IO[Option[Elem[CompositeRestart]]] =
    fetchOne(view, asc = false)

  private def fetchOne(view: ViewRef, asc: Boolean) = {
    val direction = if (asc) fr"ASC" else fr"DESC"
    sql"""SELECT ordering, project, id, value, instant from public.composite_restarts
         |WHERE project = ${view.project} and id = ${view.viewId} and acknowledged = false
         |ORDER BY ordering $direction
         |LIMIT 1""".stripMargin
      .query[(Offset, ProjectRef, Iri, Json, Instant)]
      .map { case (offset, project, id, json, instant) =>
        Elem.fromEither(entityType, id, project, instant, offset, json.as[CompositeRestart], 1)
      }
      .option
      .transact(xas.read)
  }

}

object CompositeRestartStore {
  private val logger = Logger[CompositeRestartStore]

  private val purgeCompositeRestartMetadata = ProjectionMetadata("composite-views", "purge-composite-restarts")

  /**
    * Register the task to delete expired restarts in the supervisor
    * @param store
    *   the store
    * @param config
    *   the projection config
    */
  def purgeExpiredRestarts(
      store: CompositeRestartStore,
      config: PurgeConfig
  ): PurgeProjection = PurgeProjection(purgeCompositeRestartMetadata, config, store.deleteExpired)
}
