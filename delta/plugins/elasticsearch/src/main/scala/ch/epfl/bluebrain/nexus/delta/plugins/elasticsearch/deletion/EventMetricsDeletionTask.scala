package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.EventMetricsProjection
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.parser.parse
import monix.bio.Task

/**
  * Creates a project deletion task that deletes event metrics pushed for this project
  * @param client
  *   the elasticsearch client
  * @param prefix
  *   the prefix for the elasticsearch index
  */
final class EventMetricsDeletionTask(client: ElasticSearchClient, prefix: String) extends ProjectDeletionTask {

  private val index = EventMetricsProjection.eventMetricsIndex(prefix)

  override def apply(project: ProjectRef)(implicit subject: Identity.Subject): Task[ProjectDeletionReport.Stage] =
    searchByProject(project).flatMap { search =>
      client
        .deleteByQuery(search, index)
        .as(
          ProjectDeletionReport.Stage("event-metrics", "Event metrics have been successfully deleted.")
        )
    }

  private[deletion] def searchByProject(project: ProjectRef) = Task.fromEither {
    parse(s"""{"query": {"term": {"project": "$project"} } }""").flatMap(
      _.asObject.toRight(new IllegalStateException("Failed to convert to json object the search query."))
    )
  }

}

object EventMetricsDeletionTask {
  def apply(client: ElasticSearchClient, prefix: String) = new EventMetricsDeletionTask(client, prefix)
}
