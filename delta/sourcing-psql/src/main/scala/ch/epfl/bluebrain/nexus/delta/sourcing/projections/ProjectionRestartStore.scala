package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionRestartStore.logger
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectionRestart.{entityType, restartId}
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{FailedElem, SuccessElem}
import com.typesafe.scalalogging.Logger
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.syntax.EncoderOps
import monix.bio.UIO

import java.time.Instant

/**
  * Persistent operations for projections restart
  */
final class ProjectionRestartStore(xas: Transactors, config: QueryConfig) {

  def save(restart: ProjectionRestart): UIO[Unit] =
    sql"""INSERT INTO public.projection_restarts (name, value, instant, acknowledged)
           |VALUES (${restart.name}, ${restart.asJson} ,${restart.instant}, false)
           |""".stripMargin.update.run
      .transact(xas.write)
      .void
      .hideErrors

  def acknowledge(id: Offset): UIO[Unit] =
    sql"""UPDATE public.projection_restarts SET acknowledged = true
         |WHERE ordering = ${id.value}
         |""".stripMargin.update.run
      .transact(xas.write)
      .void
      .hideErrors

  def deleteExpired(instant: Instant): UIO[Unit] =
    sql"""DELETE FROM public.projection_restarts WHERE instant < $instant""".update.run
      .transact(xas.write)
      .hideErrors
      .tapEval { deleted =>
        UIO.when(deleted > 0)(UIO.delay(logger.info(s"Deleted $deleted projection restarts.")))
      }
      .void

  def stream(offset: Offset): ElemStream[ProjectionRestart] =
    StreamingQuery
      .apply[(Offset, Json, Instant)](
        offset,
        o => sql"""SELECT ordering, value, instant from public.projection_restarts
                  |WHERE ordering > $o and acknowledged = false
                  |ORDER BY ordering ASC""".stripMargin.query[(Offset, Json, Instant)],
        _._1,
        config,
        xas
      )
      .map { case (id, json, instant) =>
        json
          .as[ProjectionRestart]
          .fold(
            err => FailedElem(entityType, restartId(id), None, instant, id, err, 1),
            restart => SuccessElem(entityType, restartId(id), None, instant, id, restart, 1)
          )
      }
}

object ProjectionRestartStore {

  private val logger: Logger = Logger[ProjectionRestartStore]

}
