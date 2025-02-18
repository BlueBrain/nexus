package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceScopeF}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant
import java.util.UUID

/**
  * State for an existing Blazegraph view.
  *
  * @param id
  *   the view id
  * @param project
  *   a reference to the parent project
  * @param uuid
  *   the unique view identifier
  * @param value
  *   the view configuration
  * @param source
  *   the last original json value provided by the caller
  * @param tags
  *   the collection of tags
  * @param rev
  *   the current revision of the view
  * @param deprecated
  *   the deprecation status of the view
  * @param createdAt
  *   the instant when the view was created
  * @param createdBy
  *   the subject that created the view
  * @param updatedAt
  *   the instant when the view was last updated
  * @param updatedBy
  *   the subject that last updated the view
  */
final case class BlazegraphViewState(
    id: Iri,
    project: ProjectRef,
    uuid: UUID,
    value: BlazegraphViewValue,
    source: Json,
    rev: Int,
    indexingRev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  override def schema: ResourceRef = model.schema

  override def types: Set[Iri] = value.tpe.types

  /**
    * Maps the current state to a [[BlazegraphView]].
    */
  lazy val asBlazegraphView: BlazegraphView = value match {
    case IndexingBlazegraphViewValue(
          name,
          description,
          resourceSchemas,
          resourceTypes,
          resourceTag,
          includeMetadata,
          includeDeprecated,
          permission
        ) =>
      IndexingBlazegraphView(
        id,
        name,
        description,
        project,
        uuid,
        resourceSchemas,
        resourceTypes,
        resourceTag,
        includeMetadata,
        includeDeprecated,
        permission,
        source,
        indexingRev
      )
    case AggregateBlazegraphViewValue(name, description, views) =>
      AggregateBlazegraphView(id, name, description, project, views, source)
  }

  def toResource: ViewResource =
    ResourceF(
      id = id,
      scope = ResourceScopeF("views", project, id),
      rev = rev,
      types = value.tpe.types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = asBlazegraphView
    )
}

object BlazegraphViewState {

  implicit val serializer: Serializer[Iri, BlazegraphViewState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                    = Serializer.circeConfiguration
    implicit val valueCodec: Codec.AsObject[BlazegraphViewValue] = deriveConfiguredCodec[BlazegraphViewValue]
    implicit val codec: Codec.AsObject[BlazegraphViewState]      = deriveConfiguredCodec[BlazegraphViewState]
    Serializer.dropNullsInjectType()
  }

}
