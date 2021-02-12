package ch.epfl.bluebrain.nexus.delta.sdk.indexing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId

import java.util.UUID

/**
  * A lens on a View
  * @tparam A the view type
  */
trait ViewLens[A] {

  /**
    * @return the view [[Iri]]
    */
  def id(view: A): Iri

  /**
    * @return the view revision
    */
  def rev(view: A): Long

  /**
    * @return the view projection id
    */
  def projectionId(view: A): ViewProjectionId

  /**
    * @return the view UUID
    */
  def uuid(view: A): UUID
}
