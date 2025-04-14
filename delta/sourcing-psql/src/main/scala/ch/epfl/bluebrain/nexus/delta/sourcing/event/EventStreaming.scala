package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.*
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.{DroppedElem, SuccessElem}
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Scope, Transactors}
import doobie.Fragments
import doobie.postgres.implicits.*
import doobie.syntax.all.*
import doobie.util.query.Query0
import io.circe.Json

import java.time.Instant

object EventStreaming {

  private val logger = Logger[EventStreaming.type]

  def fetchScoped[A](
      scope: Scope,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): ElemStream[A] =
    streamA(
      offset,
      offset => scopedEvents(types, scope, offset, config),
      xas,
      config
    )

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
      query: Offset => Query0[Elem[Json]],
      xas: Transactors,
      cfg: QueryConfig
  )(implicit md: MultiDecoder[A]): ElemStream[A] =
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
      query: Offset => Query0[Elem[Json]],
      xas: Transactors,
      cfg: QueryConfig,
      decode: (EntityType, Json) => IO[Option[A]]
  ): ElemStream[A] =
    StreamingQuery[Elem[Json]](start, query, _.offset, cfg.refreshStrategy, xas)
      // evalMapFilter re-chunks to 1, the following 2 statements do the same but preserve the chunks
      .evalMapChunk { e =>
        e.evalMapFilter { value =>
          decode(e.tpe, value).onError { case err =>
            logger.error(err)(
              s"An error occurred while decoding value with id '${e.id}' of type '${e.tpe}' in '${e.project}'."
            )
          }
        }
      }

  private def scopedEvents(
      types: List[EntityType],
      scope: Scope,
      offset: Offset,
      cfg: QueryConfig
  ): Query0[Elem[Json]] =
    fr"""((SELECT 'newEvent', type, org, project, id, value, rev, instant, ordering
        |FROM public.scoped_events
        |${eventFilter(types, scope, offset)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize})
        |UNION
        |(SELECT 'tombstone', type, org, project, id, null, -1, instant, ordering
        |FROM public.scoped_event_tombstones
        |${tombstoneFilter(types, scope, offset)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize})
        |ORDER BY ordering)
        |LIMIT ${cfg.batchSize}
        |""".stripMargin.query[(String, EntityType, Label, Label, Iri, Option[Json], Int, Instant, Offset)].map {
      case ("newEvent", entityType, org, project, id, Some(json), rev, instant, offset) =>
        SuccessElem(entityType, id, ProjectRef(org, project), instant, offset, json, rev)
      case (_, entityType, org, project, id, _, rev, instant, offset)                   =>
        DroppedElem(entityType, id, ProjectRef(org, project), instant, offset, rev)
    }

  private def typesIn(types: List[EntityType]) =
    NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

  private def eventFilter(types: List[EntityType], scope: Scope, offset: Offset) =
    Fragments.whereAndOpt(
      typesIn(types),
      scope.asFragment,
      offset.asFragment
    )

  private def tombstoneFilter(types: List[EntityType], scope: Scope, offset: Offset) =
    Fragments.whereAndOpt(
      typesIn(types),
      scope.asFragment,
      offset.asFragment
    )
}
