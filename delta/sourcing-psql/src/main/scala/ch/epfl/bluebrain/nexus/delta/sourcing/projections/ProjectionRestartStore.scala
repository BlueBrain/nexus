package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionRestartStore.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart.{entityType, restartId}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps

import java.time.Instant

/**
  * Persistent operations for projections restart
  */
final class ProjectionRestartStore(xas: Transactors, config: QueryConfig) {

  def save(restart: ProjectionRestart): IO[Unit] =
    sql"""INSERT INTO public.projection_restarts (name, value, instant, acknowledged)
           |VALUES (${restart.name}, ${restart.asJson} ,${restart.instant}, false)
           |""".stripMargin.update.run
      .transact(xas.write)
      .void

  def acknowledge(id: Offset): IO[Unit] =
    sql"""UPDATE public.projection_restarts SET acknowledged = true
         |WHERE ordering = ${id.value}
         |""".stripMargin.update.run
      .transact(xas.write)
      .void

  def deleteExpired(instant: Instant): IO[Unit] =
    sql"""DELETE FROM public.projection_restarts WHERE instant < $instant""".update.run
      .transact(xas.write)
      .flatTap { deleted =>
        IO.whenA(deleted > 0)(logger.info(s"Deleted $deleted projection restarts."))
      }
      .void

  def stream(offset: Offset): ElemStream[ProjectionRestart] =
    StreamingQuery
      .apply[(Offset, Json, Instant)](
        offset,
        o => sql"""SELECT ordering, value, instant from public.projection_restarts
                  |WHERE ordering > $o and acknowledged = false
                  |ORDER BY ordering ASC
                  |LIMIT ${config.batchSize}""".stripMargin.query[(Offset, Json, Instant)],
        _._1,
        config,
        xas
      )
      .map { case (id, json, instant) =>
        Elem.fromEither(entityType, restartId(id), None, instant, id, json.as[ProjectionRestart], 1)
      }
}

object ProjectionRestartStore {

  private val logger = Logger[ProjectionRestartStore]

}
