package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, SuccessElemStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Scope, Transactors}
import doobie.implicits._
import doobie.util.query.Query0
import doobie.{Fragment, Fragments}
import io.circe.Json

object EventStreaming {

  def fetchScoped[A](
      scope: Scope,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): SuccessElemStream[A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    streamA(
      offset,
      offset => scopedEvents(typeIn, scope, offset, config).query[Elem.SuccessElem[Json]],
      xas,
      config
    )
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
  private def streamA[A](
      start: Offset,
      query: Offset => Query0[Elem.SuccessElem[Json]],
      xas: Transactors,
      cfg: QueryConfig
  )(implicit md: MultiDecoder[A]): SuccessElemStream[A] =
    streamFA(start, query, xas, cfg, (tpe, json) => IO.pure(md.decodeJson(tpe, json).toOption))

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
  private def streamFA[A](
      start: Offset,
      query: Offset => Query0[Elem.SuccessElem[Json]],
      xas: Transactors,
      cfg: QueryConfig,
      decode: (EntityType, Json) => IO[Option[A]]
  ): SuccessElemStream[A]                                                                       =
    StreamingQuery[Elem.SuccessElem[Json]](start, query, _.offset, cfg, xas)
      // evalMapFilter re-chunks to 1, the following 2 statements do the same but preserve the chunks
      .evalMapChunk(e => decode(e.tpe, e.value).map(_.map(a => e.copy(value = a))))
      .collect { case Some(e) => e }

  private def scopedEvents(typeIn: Option[Fragment], scope: Scope, o: Offset, cfg: QueryConfig) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
        |${Fragments.whereAndOpt(typeIn, scope.asFragment, o.asFragment)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize}""".stripMargin

}
