package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.UIO

trait StoragePermissionProvider {

  def permissionFor(id: IdSegmentRef, project: ProjectRef, read: Boolean): UIO[Permission]

}
