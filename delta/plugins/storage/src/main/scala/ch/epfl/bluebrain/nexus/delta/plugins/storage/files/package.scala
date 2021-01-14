package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.File
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

package object files {

  /**
    * Type alias for a file specific resource.
    */
  type FileResource = ResourceF[File]

  /**
    * File schemas
    */
  object schemas {
    val files = iri"https://bluebrain.github.io/nexus/schemas/files.json"
  }

  /**
    * File vocabulary
    */
  val nxvFile = nxv + "File"

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("files/write")
  }

  /**
    * File contexts
    */
  object contexts {
    val files = iri"https://bluebrain.github.io/nexus/contexts/files.json"
  }
}
