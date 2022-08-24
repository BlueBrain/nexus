package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewState, BlazegraphViewValue, ViewResource}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json

import java.time.Instant
import java.util.UUID

object BlazegraphViewsGen {

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      value: BlazegraphViewValue,
      uuid: UUID = UUID.randomUUID(),
      source: Json = Json.obj(),
      rev: Int = 1,
      deprecated: Boolean = false,
      tags: Tags = Tags.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): ViewResource =
    BlazegraphViewState(
      id,
      project,
      uuid,
      value,
      source,
      tags,
      rev,
      deprecated,
      Instant.EPOCH,
      createdBy,
      Instant.EPOCH,
      updatedBy
    )
      .toResource(am, ProjectBase.unsafe(base))
}
