package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.model.ProjectLastUpdate
import doobie.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import fs2.Stream

trait ProjectLastUpdateStream {

  /**
    * Stream updates from the database
    */
  def apply(offset: Offset): Stream[IO, ProjectLastUpdate]

}

object ProjectLastUpdateStream {

  def apply(xas: Transactors, config: QueryConfig): ProjectLastUpdateStream =
    (offset: Offset) =>
      StreamingQuery[ProjectLastUpdate](
        offset,
        o => sql"""SELECT *
              |FROM project_last_updates
              |WHERE last_ordering > $o
              |ORDER BY last_ordering ASC
              |LIMIT ${config.batchSize}""".stripMargin.query[ProjectLastUpdate],
        _.lastOrdering,
        config.refreshStrategy,
        xas
      )

}
