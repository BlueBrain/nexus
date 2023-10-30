package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.StoragePermissionProvider.AccessType.{Read, Write}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

class StoragePermissionProviderImpl(storages: Storages) extends StoragePermissionProvider {
  override def permissionFor(
      id: IdSegmentRef,
      project: ProjectRef,
      accessType: StoragePermissionProvider.AccessType
  ): IO[Permission] = {
    storages
      .fetch(id, project)
      .map(storage => storage.value.storageValue)
      .map(storage =>
        accessType match {
          case Read  => storage.readPermission
          case Write => storage.writePermission
        }
      )
  }
}
