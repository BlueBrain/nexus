package ch.epfl.bluebrain.nexus.delta.sdk.generators

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.Tags
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceState}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Json

import java.time.Instant
import scala.concurrent.duration.DurationInt

object ResourceGen {

  // We put a lenient api for schemas otherwise the api checks data types before the actual schema validation process
  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  def currentState(
      id: Iri,
      project: ProjectRef,
      source: Json,
      jsonld: JsonLdResult,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty,
      rev: Int = 1,
      deprecated: Boolean = false,
      subject: Subject = Anonymous
  ) =
    ResourceState(
      id,
      project,
      project,
      source,
      jsonld.compacted,
      jsonld.expanded,
      RemoteContextRef(jsonld.remoteContexts),
      rev,
      deprecated,
      schema,
      jsonld.types,
      tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    )

  def resource(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty
  )(implicit resolution: RemoteContextResolution, runtime: IORuntime): Resource = {
    val expanded  = ExpandedJsonLd(source).accepted.replaceId(id)
    val compacted = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    Resource(id, project, tags, schema, source, compacted, expanded)
  }

  def resourceAsync(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty
  )(implicit resolution: RemoteContextResolution): IO[Resource] = {
    for {
      expanded  <- ExpandedJsonLd(source).map(_.replaceId(id))
      compacted <- expanded.toCompacted(source.topContextValueOrEmpty)
    } yield {
      Resource(id, project, tags, schema, source, compacted, expanded)
    }
  }

  def sourceToResourceF(
      id: Iri,
      project: ProjectRef,
      source: Json,
      schema: ResourceRef = Latest(schemas.resources),
      tags: Tags = Tags.empty,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  )(implicit resolution: RemoteContextResolution, runtime: IORuntime): DataResource = {
    val result         = ExpandedJsonLd.explain(source).accepted
    val expanded       = result.value.replaceId(id)
    val compacted      = expanded.toCompacted(source.topContextValueOrEmpty).accepted
    val remoteContexts = RemoteContextRef(result.remoteContexts)
    Resource(id, project, tags, schema, source, compacted, expanded)
    ResourceState(
      id,
      project,
      project,
      source,
      compacted,
      expanded,
      remoteContexts,
      rev,
      deprecated,
      schema,
      expanded.cursor.getTypes.getOrElse(Set.empty),
      tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource
  }

  def resourceFor(
      resource: Resource,
      types: Set[Iri] = Set.empty,
      rev: Int = 1,
      subject: Subject = Anonymous,
      deprecated: Boolean = false
  ): DataResource =
    ResourceState(
      resource.id,
      resource.project,
      resource.project,
      resource.source,
      resource.compacted,
      resource.expanded,
      Set.empty,
      rev,
      deprecated,
      resource.schema,
      types,
      resource.tags,
      Instant.EPOCH,
      subject,
      Instant.EPOCH,
      subject
    ).toResource

  def jsonLdContent(id: Iri, project: ProjectRef, source: Json)(implicit
      resolution: RemoteContextResolution,
      runtime: IORuntime
  ) = {
    val resourceF = sourceToResourceF(id, project, source)
    JsonLdContent(resourceF, resourceF.value.source, None)
  }

  implicit final private class CatsIOValuesOps[A](private val io: IO[A]) {
    def accepted(implicit runtime: IORuntime): A =
      io.unsafeRunTimed(45.seconds).getOrElse(throw new RuntimeException("IO timed out during .accepted call"))
  }

}
