package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.Current
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues}
import io.circe.Json
import org.scalatest.OptionValues

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
      schema.graph,
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
      tags: Map[Label, Long] = Map.empty
  )(implicit resolution: RemoteContextResolution): Schema = {
    val expanded  = JsonLd.expand(source).accepted.replaceId(id)
    val graph     = expanded.toGraph.rightValue
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Schema(id, project, tags, source, compacted, expanded, graph)
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
      schema.graph,
      rev,
      deprecated,
      schema.tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource(am, ProjectBase.unsafe(base)).value

}
