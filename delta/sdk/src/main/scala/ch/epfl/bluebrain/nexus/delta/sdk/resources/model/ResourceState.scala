package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.DataResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant
import scala.annotation.nowarn

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
  *   the instant when the organization was created
  * @param createdBy
  *   the identity that created the organization
  * @param updatedAt
  *   the instant when the organization was last updated
  * @param updatedBy
  *   the identity that last updated the organization
  */
final case class ResourceState(
    id: Iri,
    project: ProjectRef,
    schemaProject: ProjectRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: ExpandedJsonLd,
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

  def toResource(mappings: ApiMappings, base: ProjectBase): DataResource =
    ResourceF(
      id = id,
      uris = ResourceUris.resource(project, schemaProject, id, schema)(mappings, base),
      rev = rev.toLong,
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

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ResourceState] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration         = Serializer.circeConfiguration
    implicit val codec: Codec.AsObject[ResourceState] = deriveConfiguredCodec[ResourceState]
    Serializer(_.id)
  }

}
