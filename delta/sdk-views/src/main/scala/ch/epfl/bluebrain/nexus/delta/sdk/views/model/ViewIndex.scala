package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import cats.Functor
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.tracing.ProgressTracingConfig

import java.time.Instant
import java.util.UUID

/**
  * Case class describing the needed metadata to index a view
  *
  * @param projectRef
  *   the project of the view
  * @param id
  *   the identifier of the view
  * @param uuid
  *   the uuid of the view
  * @param index
  *   the name of the destination index
  * @param rev
  *   the revision of the view
  * @param deprecated
  *   if the view has been deprecated
  * @param resourceTag
  *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field are
  *   indexed with the corresponding revision
  * @param updatedAt
  *   the view created instant
  * @param value
  *   the view value
  */
final case class ViewIndex[+V](
    projectRef: ProjectRef,
    id: Iri,
    uuid: UUID,
    projectionId: ViewProjectionId,
    index: String,
    rev: Long,
    deprecated: Boolean,
    resourceTag: Option[TagLabel],
    updatedAt: Instant,
    value: V
)

object ViewIndex {
  implicit val viewIndexFunctor: Functor[ViewIndex] = new Functor[ViewIndex] {
    override def map[A, B](viewIndex: ViewIndex[A])(f: A => B): ViewIndex[B] =
      viewIndex.copy(value = f(viewIndex.value))
  }

  private val metricsPrefix: String = "delta_indexer"

  /**
    * Create a tracing config for the view
    */
  def tracingConfig(
      view: ViewIndex[_],
      viewType: Iri,
      additionalTags: Map[String, Any] = Map.empty
  ): ProgressTracingConfig =
    ProgressTracingConfig(
      metricsPrefix,
      additionalTags ++ Map(
        "project"      -> view.projectRef.toString,
        "organization" -> view.projectRef.organization.toString,
        "viewId"       -> view.id.toString,
        "type"         -> viewType.toString
      )
    )

}
