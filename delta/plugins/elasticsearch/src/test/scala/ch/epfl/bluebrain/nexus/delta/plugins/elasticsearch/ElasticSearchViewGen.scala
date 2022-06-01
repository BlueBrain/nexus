package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import org.scalatest.OptionValues

import java.time.Instant
import java.util.UUID

object ElasticSearchViewGen extends OptionValues {

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      value: ElasticSearchViewValue,
      uuid: UUID = UUID.randomUUID(),
      source: Json = Json.obj(),
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[UserTag, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): ViewResource =
    Current(id, project, uuid, value, source, tags, rev, deprecated, Instant.EPOCH, createdBy, Instant.EPOCH, updatedBy)
      .toResource(am, ProjectBase.unsafe(base), JsonObject.empty, JsonObject.empty)
      .value
}
