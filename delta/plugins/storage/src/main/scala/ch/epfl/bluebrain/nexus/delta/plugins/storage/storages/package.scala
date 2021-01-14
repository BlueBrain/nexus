package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

package object storages {

  /**
    * Type alias for a storage specific resource.
    */
  type StorageResource = ResourceF[Storage]

  /**
    * Storage schemas
    */
  object schemas {
    val storage = iri"https://bluebrain.github.io/nexus/schemas/storages.json"
  }

  /**
    * Storage contexts
    */
  object contexts {
    val storages = iri"https://bluebrain.github.io/nexus/contexts/storages.json"
  }

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("storages/write")
  }

  val nxvStorage = nxv + "Storage"

}
