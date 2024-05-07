package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdRejection.InvalidJsonLdFormat
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant

/**
  * A resource active state.
  *
  * @param id
  *   the resource identifier
  * @param project
  *   the project where the resource belongs
  * @param schemaProject
  *   the project where the schema belongs
  * @param source
  *   the representation of the resource as posted by the subject
  * @param compacted
  *   the compacted JSON-LD representation of the resource
  * @param expanded
  *   the expanded JSON-LD representation of the resource
  * @param remoteContexts
  *   the remote contexts of the resource
  * @param rev
  *   the organization revision
  * @param deprecated
  *   the deprecation status of the organization
  * @param schema
  *   the optional schema used to constrain the resource
  * @param types
  *   the collection of known resource types
  * @param tags
  *   the collection of tag aliases
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the identity that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the identity that last updated the resource
  */
final case class ResourceState(
    id: Iri,
    project: ProjectRef,
    schemaProject: ProjectRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd,
    // TODO: Remove default after 1.10 migration
    remoteContexts: Set[RemoteContextRef] = Set.empty,
    rev: Int,
    deprecated: Boolean,
    schema: ResourceRef,
    types: Set[Iri],
    tags: Tags,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  def toAssembly: Either[InvalidJsonLdFormat, JsonLdAssembly] = {
    implicit val api: JsonLdApi = JsonLdJavaApi.lenient
    expanded.toGraph
      .map { graph =>
        JsonLdAssembly(id, source, compacted, expanded, graph, remoteContexts)
      }
      .leftMap { err => InvalidJsonLdFormat(Some(id), err) }
  }

  def toResource: DataResource =
    ResourceF(
      id = id,
      uris = ResourceUris.resource(project, schemaProject, id),
      rev = rev,
      types = types,
      schema = schema,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      value = Resource(id, project, tags, schema, source, compacted, expanded)
    )
}

object ResourceState {

  implicit val serializer: Serializer[Iri, ResourceState] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

    // TODO: The `.withDefaults` method is used in order to inject the default empty remoteContexts
    //  when deserializing an event that has none. Remove it after 1.10 migration.
    implicit val configuration: Configuration         = Serializer.circeConfiguration.withDefaults
    implicit val codec: Codec.AsObject[ResourceState] = deriveConfiguredCodec[ResourceState]
    Serializer()
  }

}
