package ch.epfl.bluebrain.nexus.delta.sdk.views.syntax

import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.ProjectionProgressStreamOps
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress
import fs2.Stream
import monix.bio.Task

trait ProjectionProgressStreamSyntax {

  implicit final def projectionProgressStreamSyntax[A](
      stream: Stream[Task, ProjectionProgress[A]]
  ): ProjectionProgressStreamOps[A] = new ProjectionProgressStreamOps[A](stream)

}
