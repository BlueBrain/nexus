package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.TypeHierarchyResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.TypeHierarchy.typeHierarchyId
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant

/**
  * Enumeration of type hierarchy states.
  */
final case class TypeHierarchyState(
    mapping: TypeHierarchyMapping,
    rev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends GlobalState {

  override def id: Iri                    = typeHierarchyId
  override def schema: ResourceRef        = Latest(schemas.typeHierarchy)
  override def types: Set[IriOrBNode.Iri] = Set(nxv.TypeHierarchy)

  def toResource: TypeHierarchyResource =
    ResourceF(
      id = id,
      uris = ResourceUris.typeHierarchy,
      rev = rev,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = TypeHierarchy(mapping)
    )
}

object TypeHierarchyState {

  val serializer: Serializer[Iri, TypeHierarchyState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration              = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[TypeHierarchyState] = deriveConfiguredCodec[TypeHierarchyState]
    Serializer(identity)
  }

}
