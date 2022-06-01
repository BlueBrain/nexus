package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

trait ResourceIdCheck {
  def isAvailableOr[R](project: ProjectRef, id: Iri)(onExists: => R): IO[R, Unit]
}

object ResourceIdCheck {
  final def alwaysAvailable: ResourceIdCheck = new ResourceIdCheck {
    override def isAvailableOr[R](project: ProjectRef, id: Iri)(onExists: => R): IO[R, Unit] = IO.unit
  }

  type IdAvailability[R] = (ProjectRef, Iri) => IO[R, Unit]
}
