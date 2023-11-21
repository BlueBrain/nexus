package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.Scope.Root
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Scope, Transactors}
import doobie.implicits._
import doobie.{Fragment, Fragments}
import io.circe.Json

object EventStreaming {

  def fetchAll[A](
      scope: Scope,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    Envelope.streamA(
      offset,
      offset =>
        scope match {
          case Root =>
            sql"""(${globalEvents(typeIn, offset, config)}) UNION ALL (${scopedEvents(typeIn, scope, offset, config)})
                 |ORDER BY ordering
                 |LIMIT ${config.batchSize}""".stripMargin.query[Envelope[Json]]
          case _    => scopedEvents(typeIn, scope, offset, config).query[Envelope[Json]]
        },
      xas,
      config
    )
  }

  def fetchScoped[A](
      scope: Scope,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    Envelope.streamA(
      offset,
      offset => scopedEvents(typeIn, scope, offset, config).query[Envelope[Json]],
      xas,
      config
    )
  }

  private def globalEvents(typeIn: Option[Fragment], o: Offset, cfg: QueryConfig) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.global_events
        |${Fragments.whereAndOpt(typeIn, o.asFragment)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize}""".stripMargin

  private def scopedEvents(typeIn: Option[Fragment], scope: Scope, o: Offset, cfg: QueryConfig) =
    fr"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
        |${Fragments.whereAndOpt(typeIn, scope.asFragment, o.asFragment)}
        |ORDER BY ordering
        |LIMIT ${cfg.batchSize}""".stripMargin

}
