package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Current
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import org.scalatest.OptionValues

object ResourceGen extends OptionValues with IOValues {

  def currentState(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      types: Set[Iri] = Set.empty,
      tags: Map[Label, Long] = Map.empty,
      rev: Long = 1L,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  )(implicit resolution: RemoteContextResolution): Current = {
    val expanded  = JsonLd.expand(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source).accepted
    Current(
      id,
      project,
      source,
      compacted,
      expanded,
      rev,
      deprecated,
      schema,
      types,
      tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )
  }

  def resource(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources)
  )(implicit resolution: RemoteContextResolution): Resource = {
    val expanded  = JsonLd.expand(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source).accepted
    Resource(id, project, schema, source, compacted, expanded)
  }

  def resourceFor(
      resource: Resource,
      types: Set[Iri] = Set.empty,
      tags: Map[Label, Long] = Map.empty,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  )(implicit resolution: RemoteContextResolution): DataResource =
    currentState(
      resource.id,
      resource.project,
      resource.source,
      resource.schema,
      types,
      tags,
      rev,
      deprecated,
      subject
    ).toResource.value

}
