package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{DefaultMapping, DefaultSettings, ElasticSearchViewState, ElasticSearchViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.views.IndexingRev
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}

import java.time.Instant
import java.util.UUID

object ElasticSearchViewGen {

  def stateFor(
      id: Iri,
      project: ProjectRef,
      value: ElasticSearchViewValue,
      uuid: UUID = UUID.randomUUID(),
      source: Json = Json.obj(),
      rev: Int = 1,
      indexingRev: IndexingRev = IndexingRev.init,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): ElasticSearchViewState =
    ElasticSearchViewState(
      id,
      project,
      uuid,
      value,
      source,
      tags,
      rev,
      indexingRev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      value: ElasticSearchViewValue,
      uuid: UUID = UUID.randomUUID(),
      source: Json = Json.obj(),
      rev: Int = 1,
      indexingRev: IndexingRev = IndexingRev.init,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous
  ): ViewResource =
    stateFor(
      id,
      project,
      value,
      uuid,
      source,
      rev,
      indexingRev,
      deprecated,
      tags,
      createdBy,
      updatedBy
    ).toResource(DefaultMapping(JsonObject.empty), DefaultSettings(JsonObject.empty))
}
