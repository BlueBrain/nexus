package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider.AccessType
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Provides the permission a user needs to have in order to access files on this storage
  */
trait StoragePermissionProvider {

  def permissionFor(id: IdSegmentRef, project: ProjectRef, accessType: AccessType): IO[Permission]

}

object StoragePermissionProvider {
  sealed trait AccessType
  object AccessType {
    case object Read  extends AccessType
    case object Write extends AccessType
  }
}
