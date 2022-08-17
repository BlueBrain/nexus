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

object EventStreaming {

  def fetchAll[A](predicate: Predicate, types: List[EntityType], offset: Offset, config: QueryConfig, xas: Transactors)(
      implicit md: MultiDecoder[A]
  ): EnvelopeStream[String, A] = {
    val nel                     = NonEmptyList.fromList(types)
    def globalEvents(o: Offset) =
      fr"SELECT type, id, value, rev, instant, ordering FROM global_events" ++
        Fragments.whereAndOpt(nel.map { types => Fragments.in(fr"type", types) }, o.asFragment) ++
        fr"ORDER BY ordering" ++
        fr"LIMIT ${config.batchSize}"
    def scopedEvents(o: Offset) =
      fr"SELECT type, id, value, rev, instant, ordering FROM scoped_events" ++
        Fragments.whereAndOpt(nel.map { types => Fragments.in(fr"type", types) }, predicate.asFragment, o.asFragment) ++
        fr"ORDER BY ordering" ++
        fr"LIMIT ${config.batchSize}"

    Envelope.multipleStream[String, A](
      offset,
      (o: Offset) =>
        predicate match {
          case Root =>
            fr"(${globalEvents(o)}) UNION ALL (${scopedEvents(o)})" ++
              fr"ORDER BY ordering" ++
              fr"LIMIT ${config.batchSize}"
          case _    => scopedEvents(o)
        },
      config.refreshInterval,
      xas
    )
  }

}
