package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sourcing.MultiDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import doobie._
import doobie.postgres.circe.jsonb.implicits._
import doobie.postgres.implicits._
import doobie.util.query.Query0
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

  @nowarn("cat=unused")
  implicit def envelopeRead[Id, Value](implicit g: Get[Id], s: Decoder[Value]): Read[Envelope[Id, Value]] = {
    implicit val v: Get[Value] = pgDecoderGetT[Value]
    Read[(EntityType, Id, Value, Int, Instant, Long)].map { case (tpe, id, value, rev, instant, offset) =>
      Envelope(tpe, id, rev, value, instant, Offset.at(offset))
    }
  }

  /**
    * Stream results for the provided query from the start offset. The refresh strategy in the query configuration
    * defines if the stream will re-execute the query with a delay after all the results have been consumed. Failure to
    * decode a stream element (from json to A) will drop the element silently.
    *
    * @param start
    *   the start offset
    * @param query
    *   the query function for an offset
    * @param xas
    *   the transactor instances
    * @param cfg
    *   the query configuration
    * @param md
    *   a decoder collection indexed on the entity type for values of type A.
    * @tparam A
    *   the underlying value type
    */
  def streamA[A](
      start: Offset,
      query: Offset => Query0[Envelope[String, Json]],
      xas: Transactors,
      cfg: QueryConfig
  )(implicit md: MultiDecoder[A]): EnvelopeStream[String, A] =
    streamFA(start, query, xas, cfg, (tpe, json) => Task.pure(md.decodeJson(tpe, json).toOption))

  /**
    * Stream results for the provided query from the start offset. The refresh strategy in the query configuration
    * defines if the stream will re-execute the query with a delay after all the results have been consumed.
    *
    * @param start
    *   the start offset
    * @param query
    *   the query function for an offset
    * @param xas
    *   the transactor instances
    * @param cfg
    *   the query configuration
    * @param decode
    *   a decode function
    * @tparam A
    *   the underlying value type
    */
  def streamFA[A](
      start: Offset,
      query: Offset => Query0[Envelope[String, Json]],
      xas: Transactors,
      cfg: QueryConfig,
      decode: (EntityType, Json) => Task[Option[A]]
  ): EnvelopeStream[String, A] =
    StreamingQuery[Envelope[String, Json]](start, query, _.offset, cfg, xas)
      // evalMapFilter re-chunks to 1, the following 2 statements do the same but preserve the chunks
      .evalMapChunk(e => decode(e.tpe, e.value).map(_.map(a => e.copy(value = a))))
      .collect { case Some(e) => e }

}
