package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion.EventMetricsDeletionTask.report
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.metrics.EventMetrics
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}

/**
  * Creates a project deletion task that deletes event metrics pushed for this project
  */
final class EventMetricsDeletionTask(eventMetrics: EventMetrics) extends ProjectDeletionTask {

  override def apply(project: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectDeletionReport.Stage] =
    eventMetrics.deleteByProject(project).as(report)

}

object EventMetricsDeletionTask {
  private val report = ProjectDeletionReport.Stage("event-metrics", "Event metrics have been successfully deleted.")

  def apply(eventMetrics: EventMetrics) = new EventMetricsDeletionTask(eventMetrics)
}
