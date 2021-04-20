package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{NonEmptyList, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues}
import io.circe.Json
import org.scalatest.OptionValues

import java.time.Instant

object SchemaGen extends OptionValues with IOValues with EitherValuable {

  def currentState(
      schema: Schema,
      rev: Long = 1L,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  ): Current = {
    Current(
      schema.id,
      schema.project,
      schema.source,
      schema.compacted,
      schema.expanded,
      rev,
      deprecated,
      schema.tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )
  }

  def schema(
      id: Iri,
      project: ProjectRef,
      source: Json,
      tags: Map[TagLabel, Long] = Map.empty
  )(implicit resolution: RemoteContextResolution): Schema = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Schema(id, project, tags, source, compacted, NonEmptyList.of(expanded))
  }

  def resourceFor(
      schema: Schema,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): SchemaResource =
    Current(
      schema.id,
      schema.project,
      schema.source,
      schema.compacted,
      schema.expanded,
      rev,
      deprecated,
      schema.tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource(am, ProjectBase.unsafe(base)).value

}
