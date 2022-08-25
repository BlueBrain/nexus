package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

import java.time.Instant

final class BlazegraphOnEventInstant extends OnEventInstant {

  override def awakeIndexingStream(
      project: ProjectRef,
      prevEvent: Option[Instant],
      currentEvent: Instant
  ): Task[Unit] = Task.unit

}
