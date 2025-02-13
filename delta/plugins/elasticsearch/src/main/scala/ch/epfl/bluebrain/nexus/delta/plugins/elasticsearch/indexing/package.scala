package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{IndexAlias, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.DefaultIndexConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.PartitionInit
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionMetadata
import io.circe.syntax.KeyOps
import io.circe.{Json, JsonObject}

package object indexing {

  val defaultIndexingContext: ContextValue = ContextValue(contexts.elasticsearchIndexing, contexts.indexingMetadata)

  def defaultProjectTargetAlias(config: DefaultIndexConfig, project: ProjectRef): IndexLabel =
    IndexLabel.unsafe(s"${config.prefix}_${config.name}_${PartitionInit.projectRefHash(project)}")

  private def projectFilter(project: ProjectRef)(implicit baseUri: BaseUri): JsonObject                     =
    JsonObject("term" := Json.obj("_project" := ResourceUris.project(project).accessUri))

  def indexingAlias(config: DefaultIndexConfig, project: ProjectRef)(implicit baseUri: BaseUri): IndexAlias =
    IndexAlias(
      config.index,
      defaultProjectTargetAlias(config, project),
      Some(project.toString),
      Some(projectFilter(project))
    )

  val defaultIndexingId: IriOrBNode.Iri = nxv + "default-indexing"

  def defaultIndexingProjection(ref: ProjectRef): String = s"default-indexing-$ref"

  def defaultIndexingProjectionMetadata(project: ProjectRef): ProjectionMetadata = ProjectionMetadata(
    "default-indexing",
    defaultIndexingProjection(project),
    Some(project),
    Some(defaultIndexingId)
  )
}
