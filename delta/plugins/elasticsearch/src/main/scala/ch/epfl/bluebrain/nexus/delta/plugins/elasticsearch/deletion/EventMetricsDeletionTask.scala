package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.parser.parse

/**
  * Creates a project deletion task that deletes event metrics pushed for this project
  * @param client
  *   the elasticsearch client
  * @param index
  *   the name of the index for metrics
  */
final class EventMetricsDeletionTask(client: ElasticSearchClient, index: IndexLabel) extends ProjectDeletionTask {

  override def apply(project: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectDeletionReport.Stage] =
    searchByProject(project).flatMap { search =>
      client
        .deleteByQuery(search, index)
        .as(ProjectDeletionReport.Stage("event-metrics", "Event metrics have been successfully deleted."))
    }

  private[deletion] def searchByProject(project: ProjectRef) = IO.fromEither {
    parse(s"""{"query": {"term": {"project": "$project"} } }""").flatMap(
      _.asObject.toRight(new IllegalStateException("Failed to convert to json object the search query."))
    )
  }

}

object EventMetricsDeletionTask {
  def apply(client: ElasticSearchClient, index: IndexLabel) = new EventMetricsDeletionTask(client, index)
}
