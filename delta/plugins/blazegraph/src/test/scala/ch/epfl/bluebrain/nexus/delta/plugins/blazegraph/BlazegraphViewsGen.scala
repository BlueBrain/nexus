package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState.Current
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import io.circe.Json
import org.scalatest.OptionValues

import java.time.Instant
import java.util.UUID

object BlazegraphViewsGen extends OptionValues {

  def resourceFor(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: BlazegraphViewValue,
      source: Json = Json.obj(),
      rev: Long = 1L,
      deprecated: Boolean = false,
      tags: Map[TagLabel, Long] = Map.empty,
      createdBy: Subject = Anonymous,
      updatedBy: Subject = Anonymous,
      am: ApiMappings = ApiMappings.default,
      base: Iri = nxv.base
  ): BlazegraphViewResource =
    Current(id, project, uuid, value, source, tags, rev, deprecated, Instant.EPOCH, createdBy, Instant.EPOCH, updatedBy)
      .toResource(am, ProjectBase.unsafe(base))
      .value
}
