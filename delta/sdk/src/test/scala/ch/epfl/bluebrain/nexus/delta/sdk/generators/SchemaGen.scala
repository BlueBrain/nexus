package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, ResourceF}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import io.circe.Json
import org.scalatest.OptionValues

object SchemaGen extends OptionValues with IOValues {

  def schema(
      id: Iri,
      project: ProjectRef,
      source: Json
  )(implicit resolution: RemoteContextResolution): Schema = {
    val expanded  = JsonLd.expand(source).accepted.replaceId(id)
    val graph     = expanded.toGraph.accepted
    val compacted = expanded.toCompacted(source).accepted
    Schema(id, project, source, compacted, graph)
  }

  def resourceFor(
      schema: Schema,
      rev: Long = 1L,
      subject: Subject = Anonymous,
      deprecated: Boolean = false,
      am: ApiMappings = ApiMappings.empty,
      base: Iri = nxv.base
  ): SchemaResource =
    ResourceF(
      id = AccessUrl.schema(schema.project, schema.id)(_).iri,
      accessUrl = AccessUrl.schema(schema.project, schema.id)(_).shortForm(am, ProjectBase(base)),
      rev = rev,
      types = Set(nxv.Schema),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = subject,
      updatedAt = Instant.EPOCH,
      updatedBy = subject,
      schema = Latest(schema.id),
      schema
    )

}
