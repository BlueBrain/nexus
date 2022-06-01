package ch.epfl.bluebrain.nexus.delta.sdk.views.model

import cats.Functor
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Case class describing the needed metadata to index a view
  *
  * @param projectRef
  *   the project of the view
  * @param id
  *   the identifier of the view
  * @param index
  *   the name of the destination index
  * @param rev
  *   the revision of the view
  * @param deprecated
  *   if the view has been deprecated
  * @param resourceTag
  *   an optional tag to consider for indexing; when set, all resources that are tagged with the value of the field are
  *   indexed with the corresponding revision
  * @param value
  *   the view value
  */
final case class ViewIndex[+V](
    projectRef: ProjectRef,
    id: Iri,
    projectionId: ViewProjectionId,
    index: String,
    rev: Long,
    deprecated: Boolean,
    resourceTag: Option[UserTag],
    value: V
)

object ViewIndex {
  implicit val viewIndexFunctor: Functor[ViewIndex] = new Functor[ViewIndex] {
    override def map[A, B](viewIndex: ViewIndex[A])(f: A => B): ViewIndex[B] =
      viewIndex.copy(value = f(viewIndex.value))
  }

  private val metricsPrefix: String = "delta_indexer"

  /**
    * Create a metrics config for the view
    */
  def metricsConfig(
      view: ViewIndex[_],
      viewType: Iri,
      additionalTags: Map[String, Any] = Map.empty
  ): KamonMetricsConfig =
    KamonMetricsConfig(
      metricsPrefix,
      additionalTags ++ Map(
        "project"      -> view.projectRef.toString,
        "organization" -> view.projectRef.organization.toString,
        "viewId"       -> view.id.toString,
        "type"         -> viewType.toString
      )
    )

}
