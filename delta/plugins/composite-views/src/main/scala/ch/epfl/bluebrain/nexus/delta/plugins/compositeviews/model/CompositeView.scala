package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
  * Representation of a composite view.
  *
  * @param id               the id of the project
  * @param project          the project to which this view belongs
  * @param sources          the collection of sources for the view
  * @param projections      the collection of projections for the view
  * @param rebuildStrategy  the rebuild strategy of the view
  * @param uuid             the uuid of the view
  * @param tags             the tag -> rev mapping
  * @param source           the original json document provided at creation or update
  */
final case class CompositeView(
    id: Iri,
    project: ProjectRef,
    sources: Set[CompositeViewSource],
    projections: Set[CompositeViewProjection],
    rebuildStrategy: RebuildStrategy,
    uuid: UUID,
    tags: Map[TagLabel, Long],
    source: Json
)

object CompositeView {

  /**
    * The rebuild strategy for a [[CompositeView]].
    */
  sealed trait RebuildStrategy extends Product with Serializable

  /**
    * Rebuild strategy defining rebuilding at a certain interval.
    */
  final case class Interval(value: FiniteDuration) extends RebuildStrategy
}
