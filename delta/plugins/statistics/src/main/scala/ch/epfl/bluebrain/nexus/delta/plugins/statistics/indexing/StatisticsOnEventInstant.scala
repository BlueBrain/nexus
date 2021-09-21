package ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.Statistics
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.config.StatisticsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.indexing.StatisticsIndexingCoordinator.StatisticsIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.OnEventInstant
import monix.bio.Task

import java.time.Instant
import scala.concurrent.duration._

final class StatisticsOnEventInstant(
    config: StatisticsConfig,
    coordinator: StatisticsIndexingCoordinator
) extends OnEventInstant {

  override def awakeIndexingStream(
      project: ProjectRef,
      prevEvent: Option[Instant],
      currentEvent: Instant
  ): Task[Unit] = {
    val idleTimeout = config.idleTimeout.minus(1.minute) // allow for some tolerance
    Task.when(currentEvent.diff(prevEvent.getOrElse(currentEvent)).gteq(idleTimeout)) {
      coordinator.run(Statistics.typeStats, project, 1)
    }
  }
}
