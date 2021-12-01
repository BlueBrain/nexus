package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris, TagLabel}
import io.circe.Json

import java.time.Instant
import java.util.UUID

sealed trait CompositeViewState extends Product with Serializable {

  /**
    * @return
    *   the current view revision
    */
  def rev: Long

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase): Option[ViewResource]
}

object CompositeViewState {

  /**
    * Initial state of a composite view.
    */
  final case object Initial extends CompositeViewState {
    override val rev: Long = 0L

    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ViewResource] = None
  }

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
  final case class Current(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: CompositeViewValue,
      source: Json,
      tags: Map[TagLabel, Long],
      rev: Long,
      deprecated: Boolean,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends CompositeViewState {

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
    override def toResource(mappings: ApiMappings, base: ProjectBase): Option[ViewResource] = Some(
      ResourceF(
        id = id,
        uris = ResourceUris("views", project, id)(mappings, base),
        rev = rev,
        types = Set(nxv.View, compositeViewType),
        deprecated = deprecated,
        createdAt = createdAt,
        createdBy = createdBy,
        updatedAt = updatedAt,
        updatedBy = updatedBy,
        schema = schema,
        value = asCompositeView
      )
    )
  }

  implicit val revisionLens: Lens[CompositeViewState, Long] = (s: CompositeViewState) => s.rev

}
