package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.DefaultIndexConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.defaultProjectTargetAlias
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.parser.parse

final class DefaultIndexDeletionTask(client: ElasticSearchClient, defaultIndexConfig: DefaultIndexConfig)(implicit
    baseUri: BaseUri
) extends ProjectDeletionTask {

  private val reportStage =
    ProjectDeletionReport.Stage("default-index", "The project has been successfully removed from the default index.")

  override def apply(project: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectDeletionReport.Stage] = {
    val targetIndex = defaultIndexConfig.index
    val targetAlias = defaultProjectTargetAlias(defaultIndexConfig, project)
    searchByProject(project).flatMap { search =>
      client.removeAlias(targetIndex, targetAlias) >>
        client
          .deleteByQuery(search, defaultIndexConfig.index)
          .as(reportStage)
    }
  }

  private[deletion] def searchByProject(project: ProjectRef) =
    IO.fromEither {
      parse(s"""{"query": {"term": {"_project": "${ResourceUris.project(project).accessUri}"} } }""").flatMap(
        _.asObject.toRight(new IllegalStateException("Failed to convert to json object the search query."))
      )
    }
}
