package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexAlias, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

package object indexing {

  val defaultIndexingContext: ContextValue = ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  def mainProjectTargetAlias(index: IndexLabel, project: ProjectRef): IndexLabel =
    IndexLabel.unsafe(s"${index.value}_${ProjectRef.hash(project)}")

  private def projectFilter(project: ProjectRef): JsonObject                =
    JsonObject("term" := Json.obj("_project" := project))

  def mainIndexingAlias(index: IndexLabel, project: ProjectRef): IndexAlias =
    IndexAlias(
      index,
      mainProjectTargetAlias(index, project),
      Some(project.toString),
      Some(projectFilter(project))
    )

  val mainIndexingId: IriOrBNode.Iri = nxv + "main-indexing"

  def mainIndexingProjection(ref: ProjectRef): String = s"main-indexing-$ref"

  def mainIndexingProjectionMetadata(project: ProjectRef): ProjectionMetadata = ProjectionMetadata(
    "main-indexing",
    mainIndexingProjection(project),
    Some(project),
    Some(mainIndexingId)
  )
}
