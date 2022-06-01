package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalytics
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsIndexingCoordinator.GraphAnalyticsIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration._

final class GraphAnalyticsOnEventInstant(
    config: GraphAnalyticsConfig,
    coordinator: GraphAnalyticsIndexingCoordinator
) extends OnEventInstant {

  override def awakeIndexingStream(
      project: ProjectRef,
      prevEvent: Option[Instant],
      currentEvent: Instant
  ): Task[Unit] = {
    val idleTimeout = config.idleTimeout.minus(1.minute) // allow for some tolerance
    Task.when(currentEvent.diff(prevEvent.getOrElse(currentEvent)).gteq(idleTimeout)) {
      coordinator.run(GraphAnalytics.typeStats, project, 1)
    }
  }
}
