package ch.epfl.bluebrain.nexus.ship.error

import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

sealed abstract class ShipError(reason: String) extends Exception { self =>
  override def fillInStackTrace(): Throwable = self

  override def getMessage: String = reason
}

object ShipError {

  final case class ProjectDeletionIsNotAllowed(project: ProjectRef)
      extends ShipError(s"'$project' is not allowed during import.")

}
