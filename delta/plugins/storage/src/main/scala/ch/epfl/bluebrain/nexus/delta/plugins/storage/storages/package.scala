package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts as nxvContexts, nxv, schemas as nxvSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission

package object storages {

  /**
    * Type alias for a storage specific resource.
    */
  type StorageResource = ResourceF[Storage]

  /**
    * Storage schemas
    */
  object schemas {
    val storage = nxvSchema + "storages.json"
  }

  /**
    * Storage contexts
    */
  object contexts {
    val storages         = nxvContexts + "storages.json"
    val storagesMetadata = nxvContexts + "storages-metadata.json"
  }

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("storages/write")
  }

  val nxvStorage = nxv + "Storage"

  /**
    * The id for the default storage
    */
  final val defaultStorageId = nxv + "diskStorageDefault"

  /**
    * The id for the default S3 storage
    */
  final val defaultS3StorageId = nxv + "defaultS3Storage"

}
