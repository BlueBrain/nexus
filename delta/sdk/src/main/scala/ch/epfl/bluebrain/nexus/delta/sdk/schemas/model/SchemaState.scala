package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, Tags}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * A schema state.
  *
  * @param id
  *   the schema identifier
  * @param project
  *   the project where the schema belongs
  * @param source
  *   the representation of the schema as posted by the subject
  * @param compacted
  *   the compacted JSON-LD representation of the schema
  * @param expanded
  *   the expanded JSON-LD representation of the schema with the imports resolutions applied
  * @param rev
  *   the organization revision
  * @param deprecated
  *   the deprecation status of the organization
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
final case class SchemaState(
    id: Iri,
    project: ProjectRef,
    source: Json,
    compacted: CompactedJsonLd,
    expanded: NonEmptyList[ExpandedJsonLd],
    rev: Int,
    deprecated: Boolean,
    tags: Tags,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  /**
    * @return
    *   the schema reference that schemas conforms to
    */
  def schema: ResourceRef = Latest(schemas.shacl)

  /**
    * @return
    *   the collection of known types of schema resources
    */
  def types: Set[Iri] = Set(nxv.Schema)

  def toResource(mappings: ApiMappings, base: ProjectBase): SchemaResource =
    ResourceF(
      id = id,
      uris = ResourceUris.schema(project, id)(mappings, base),
      rev = rev.toLong,
      types = types,
      schema = schema,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      value = Schema(id, project, tags, source, compacted, expanded)
    )
}

object SchemaState {

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, SchemaState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    implicit val configuration: Configuration       = Serializer.circeConfiguration
    implicit val codec: Codec.AsObject[SchemaState] = deriveConfiguredCodec[SchemaState]
    Serializer(_.id)
  }

}
