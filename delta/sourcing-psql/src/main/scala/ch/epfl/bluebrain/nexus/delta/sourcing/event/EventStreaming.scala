package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Root
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import doobie.implicits._
import doobie.{Fragment, Fragments}
import io.circe.Json

object EventStreaming {

  def fetchAll[A](
      predicate: Predicate,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    Envelope.streamA(
      offset,
      offset =>
        predicate match {
          case Root =>
            sql"""(${globalEvents(typeIn, offset)}) UNION ALL (${scopedEvents(typeIn, predicate, offset)})
                 |ORDER BY ordering
                 |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[Json]]
          case _    => scopedEvents(typeIn, predicate, offset).query[Envelope[Json]]
        },
      xas,
      config
    )
  }

  def fetchScoped[A](
      predicate: Predicate,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    Envelope.streamA(
      offset,
      offset => scopedEventsLimited(typeIn, predicate, offset, config).query[Envelope[Json]],
      xas,
      config
    )
  }

  private def globalEvents(typeIn: Option[Fragment], o: Offset) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.global_events
        |${Fragments.whereAndOpt(typeIn, o.asFragment)}
        |ORDER BY ordering""".stripMargin

  private def scopedEvents(typeIn: Option[Fragment], predicate: Predicate, o: Offset) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
        |${Fragments.whereAndOpt(typeIn, predicate.asFragment, o.asFragment)}
        |ORDER BY ordering""".stripMargin

  private def scopedEventsLimited(typeIn: Option[Fragment], predicate: Predicate, o: Offset, cfg: QueryConfig) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
        |${Fragments.whereAndOpt(typeIn, predicate.asFragment, o.asFragment)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize}""".stripMargin

}
