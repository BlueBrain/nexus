package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import doobie._
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import fs2.{Chunk, Stream}
import io.circe.{Decoder, Json}
import monix.bio.Task

import java.time.Instant
import scala.annotation.nowarn

/**
  * Envelope adding metadata along the event/state
  * @param tpe
  *   the entity type
  * @param id
  *   the identifier
  * @param rev
  *   the revision
  * @param value
  *   the event/state
  * @param instant
  *   the instant
  * @param offset
  *   the offset
  */
final case class Envelope[Id, +Value](
    tpe: EntityType,
    id: Id,
    rev: Int,
    value: Value,
    instant: Instant,
    offset: Offset
) {

  def valueClass: String = ClassUtils.simpleName(value)

}

object Envelope {

  def stream[Id, Value](offset: Offset, query: Offset => Fragment, strategy: RefreshStrategy, xas: Transactors)(implicit
      @nowarn("cat=unused") get: Get[Id],
      decoder: Decoder[Value]
  ): EnvelopeStream[Id, Value] =
    Stream.unfoldChunkEval[Task, Offset, Envelope[Id, Value]](offset) { currentOffset =>
      query(currentOffset).query[(EntityType, Id, Json, Int, Instant, Long)].to[List].transact(xas.streaming).flatMap {
        rows =>
          Task
            .fromEither(
              rows
                .traverse { case (entityType, id, value, rev, instant, offset) =>
                  value.as[Value].map { event =>
                    Envelope(
                      entityType,
                      id,
                      rev,
                      event,
                      instant,
                      Offset.At(offset)
                    )
                  }
                }
            )
            .flatMap { envelopes =>
              envelopes.lastOption.fold(
                strategy match {
                  case RefreshStrategy.Stop         => Task.none
                  case RefreshStrategy.Delay(value) =>
                    Task.sleep(value) >> Task.some((Chunk.empty[Envelope[Id, Value]], currentOffset))
                }
              ) { last => Task.some((Chunk.seq(envelopes), last.offset)) }
            }
      }
    }

}
