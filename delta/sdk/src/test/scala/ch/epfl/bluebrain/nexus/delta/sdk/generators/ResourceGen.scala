package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.ResourceState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceRef, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import org.scalatest.OptionValues

import java.time.Instant

object ResourceGen extends OptionValues with IOValues {

  def currentState(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      types: Set[Iri] = Set.empty,
      tags: Map[TagLabel, Long] = Map.empty,
      rev: Long = 1L,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  )(implicit resolution: RemoteContextResolution): Current = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Current(
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
      tags: Map[TagLabel, Long] = Map.empty
  )(implicit resolution: RemoteContextResolution): Resource = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Resource(id, project, tags, schema, source, compacted, expanded)
  }

  def resourceFor(
      resource: Resource,
      types: Set[Iri] = Set.empty,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): DataResource =
    Current(
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
    ).toResource(am, ProjectBase.unsafe(base)).value

}
