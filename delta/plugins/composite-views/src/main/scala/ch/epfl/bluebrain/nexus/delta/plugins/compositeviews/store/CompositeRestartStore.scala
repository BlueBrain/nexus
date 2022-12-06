package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.CompositeRestartStore.logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{ProjectionConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.IriInstances._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.{Task, UIO}

import java.time.Instant

/**
  * Store to handle composite views restarts
  */
final class CompositeRestartStore private[store] (xas: Transactors, config: QueryConfig) {

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
    * Stream composite restarts for a given composite view
    * @param view
    *   the view reference
    * @param offset
    *   the offset
    */
  def stream(view: ViewRef, offset: Offset): ElemStream[CompositeRestart] =
    StreamingQuery
      .apply[(Offset, ProjectRef, Iri, Json, Instant)](
        offset,
        o => sql"""SELECT ordering, project, id, value, instant from public.composite_restarts
                  |WHERE ordering > $o and project = ${view.project} and id = ${view.viewId}  and acknowledged = false
                  |ORDER BY ordering ASC""".stripMargin.query,
        _._1,
        config,
        xas
      )
      .map { case (offset, project, id, json, instant) =>
        Elem.fromEither(entityType, id, Some(project), instant, offset, json.as[CompositeRestart], 1)
      }

}

object CompositeRestartStore {
  private val logger: Logger = Logger[CompositeRestartStore]

  private val purgeCompositeRestartMetadata = ProjectionMetadata("composite-views", "purge-composite-restarts")

  /**
    * Create a [[CompositeRestartStore]] and register the task to delete expired restarts in the supervisor
    *
    * @param supervisor
    *   the supervisor
    * @param xas
    *   the transactors
    * @param config
    *   the projection config
    */
  def apply(supervisor: Supervisor, xas: Transactors, config: ProjectionConfig)(implicit
      clock: Clock[UIO]
  ): Task[CompositeRestartStore] = {
    val store = new CompositeRestartStore(xas, config.query)

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
      .as(store)
  }
}
