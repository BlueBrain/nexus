package ch.epfl.bluebrain.nexus.delta.sourcing.state

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.MultiDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Predicate.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, EnvelopeStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import doobie.Fragments
import doobie.implicits._
import doobie.postgres.circe.jsonb.implicits.jsonbGet
import doobie.util.query.Query0
import io.circe.Json

object StateStreaming {

  /**
    * Stream scoped states of type A using project, tag and offset criteria.
    * @param project
    *   the resource parent project
    * @param tag
    *   the state tag
    * @param offset
    *   the starting offset
    * @param config
    *   the query configuration
    * @param xas
    *   the transactor instances
    * @param md
    *   decoder of type A indexed by entity type
    * @tparam A
    *   the state type
    */
  def scopedStates[A](
      project: ProjectRef,
      tag: Tag,
      offset: Offset,
      config: QueryConfig,
      xas: Transactors
  )(implicit md: MultiDecoder[A]): EnvelopeStream[String, A] = {
    def query(offset: Offset): Query0[Envelope[String, Json]] = {
      val where = Fragments.whereAndOpt(Project(project).asFragment, Some(fr"tag = $tag"), offset.asFragment)
      sql"""SELECT type, id, value, rev, instant, ordering FROM public.scoped_states
           |$where
           |ORDER BY ordering""".stripMargin.query[Envelope[String, Json]]
    }
    Envelope.streamA(offset, query, xas, config)
  }

}
