package ch.epfl.bluebrain.nexus.delta.sdk.generators

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.{Schema, SchemaState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import ch.epfl.bluebrain.nexus.testkit.ce.CatsIOValues
import io.circe.Json

import java.time.Instant

object SchemaGen extends CatsIOValues with EitherValuable {
  // We put a lenient api for schemas otherwise the api checks data types before the actual schema validation process
  implicit val api: JsonLdApi = JsonLdJavaApi.lenient

  def currentState(
      schema: Schema,
      rev: Int = 1,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  ): SchemaState = {
    SchemaState(
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
      tags: Tags = Tags.empty
  )(implicit resolution: RemoteContextResolution): Schema = {
    schemaAsync(id, project, source, tags).accepted
  }

  def schemaAsync(
      id: Iri,
      project: ProjectRef,
      source: Json,
      tags: Tags = Tags.empty
  )(implicit resolution: RemoteContextResolution): IO[Schema] = {
    for {
      expanded  <- ExpandedJsonLd(source).map(_.replaceId(id))
      compacted <- expanded.toCompacted(source.topContextValueOrEmpty)
    } yield {
      Schema(id, project, tags, source, compacted, NonEmptyList.of(expanded))
    }
  }

  def resourceFor(
      schema: Schema,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): SchemaResource =
    SchemaState(
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
    ).toResource

}
