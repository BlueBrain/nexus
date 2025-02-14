package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.deletion

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.mainProjectTargetAlias
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef}
import io.circe.parser.parse

final class MainIndexDeletionTask(client: ElasticSearchClient, mainIndex: IndexLabel)(implicit
    baseUri: BaseUri
) extends ProjectDeletionTask {

  private val reportStage =
    ProjectDeletionReport.Stage("default-index", "The project has been successfully removed from the default index.")

  override def apply(project: ProjectRef)(implicit subject: Identity.Subject): IO[ProjectDeletionReport.Stage] = {
    val targetAlias = mainProjectTargetAlias(mainIndex, project)
    searchByProject(project).flatMap { search =>
      client.removeAlias(mainIndex, targetAlias) >>
        client
          .deleteByQuery(search, mainIndex)
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
