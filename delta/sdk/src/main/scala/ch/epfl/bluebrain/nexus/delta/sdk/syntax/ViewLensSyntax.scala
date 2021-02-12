package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import ch.epfl.bluebrain.nexus.delta.sdk.indexing.ViewLens
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId

import java.util.UUID

trait ViewLensSyntax {
  implicit final def viewLensSyntax[A: ViewLens](value: A): ViewLensOps[A] = new ViewLensOps(value)
}

final class ViewLensOps[A](private val value: A)(implicit lens: ViewLens[A]) {
  def rev: Long                      = lens.rev(value)
  def projectionId: ViewProjectionId = lens.projectionId(value)
  def uuid: UUID                     = lens.uuid(value)
}
