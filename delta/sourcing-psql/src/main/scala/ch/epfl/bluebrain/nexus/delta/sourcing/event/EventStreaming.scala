package ch.epfl.bluebrain.nexus.delta.sourcing.event

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Root
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.{MultiDecoder, Predicate}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Envelope, EnvelopeStream}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import doobie.Fragments
import doobie.implicits._
import io.circe.Json

object EventStreaming {

  def fetchAll[A](
      predicate: Predicate,
      types: List[EntityType],
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[String, A] = {
    val typeIn = NonEmptyList.fromList(types).map { types => Fragments.in(fr"type", types) }

    def globalEvents(o: Offset) =
      fr"""SELECT type, id, value, rev, instant, ordering FROM public.global_events
           |${Fragments.whereAndOpt(typeIn, o.asFragment)}
           |ORDER BY ordering""".stripMargin

    def scopedEvents(o: Offset) =
      fr"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_events
           |${Fragments.whereAndOpt(typeIn, predicate.asFragment, o.asFragment)}
           |ORDER BY ordering""".stripMargin

    Envelope.streamA(
      offset,
      offset =>
        predicate match {
          case Root =>
            sql"""(${globalEvents(offset)}) UNION ALL (${scopedEvents(offset)})
                 |ORDER BY ordering""".stripMargin.query[Envelope[String, Json]]
          case _    => scopedEvents(offset).query[Envelope[String, Json]]
        },
      xas,
      config
    )
  }

}
