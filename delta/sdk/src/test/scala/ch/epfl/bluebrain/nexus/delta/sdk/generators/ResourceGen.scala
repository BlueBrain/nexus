package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json

import java.time.Instant

object ResourceGen extends IOValues {

  // We put a lenient api for schemas otherwise the api checks data types before the actual schema validation process
  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  def currentState(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      types: Set[Iri] = Set.empty,
      tags: Tags = Tags.empty,
      rev: Int = 1,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  )(implicit resolution: RemoteContextResolution): ResourceState = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    ResourceState(
      id,
      project,
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
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty
  )(implicit resolution: RemoteContextResolution): Resource = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Resource(id, project, tags, schema, source, compacted, expanded)
  }

  def sourceToResourceF(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  )(implicit resolution: RemoteContextResolution): DataResource = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Resource(id, project, tags, schema, source, compacted, expanded)
    ResourceState(
      id,
      project,
      project,
      source,
      compacted,
      expanded,
      rev,
      deprecated,
      schema,
      expanded.cursor.getTypes.getOrElse(Set.empty),
      tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource(am, ProjectBase.unsafe(base))
  }

  def resourceFor(
      resource: Resource,
      types: Set[Iri] = Set.empty,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): DataResource =
    ResourceState(
      resource.id,
      resource.project,
      resource.project,
      resource.source,
      resource.compacted,
      resource.expanded,
      rev,
      deprecated,
      resource.schema,
      types,
      resource.tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource(am, ProjectBase.unsafe(base))

}
