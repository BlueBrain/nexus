package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.crypto.Crypto
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
import java.util.UUID
import scala.annotation.nowarn

/**
  * State for an existing composite view.
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
final case class CompositeViewState(
    id: Iri,
    project: ProjectRef,
    uuid: UUID,
    value: CompositeViewValue,
    source: Json,
    tags: Tags,
    rev: Int,
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  def schema: ResourceRef = model.schema

  override def types: Set[Iri] = Set(nxv.View, compositeViewType)

  lazy val asCompositeView: CompositeView = CompositeView(
    id,
    project,
    value.sources,
    value.projections,
    value.rebuildStrategy,
    uuid,
    tags,
    source,
    updatedAt
  )

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): ViewResource =
    ResourceF(
      id = id,
      uris = ResourceUris("views", project, id)(mappings, base),
      rev = rev.toLong,
      types = Set(nxv.View, compositeViewType),
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = asCompositeView
    )
}

object CompositeViewState {

  @nowarn("cat=unused")
  def serializer(crypto: Crypto): Serializer[Iri, CompositeViewState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                       = Serializer.circeConfiguration
    implicit val compositeViewValueCodec: Codec[CompositeViewValue] = CompositeViewValue.databaseCodec(crypto)
    implicit val codec: Codec.AsObject[CompositeViewState]          = deriveConfiguredCodec[CompositeViewState]
    Serializer(_.id)
  }
}
