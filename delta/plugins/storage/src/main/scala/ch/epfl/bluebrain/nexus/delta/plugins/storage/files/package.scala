package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.File
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts, nxv, schemas => nxvSchema}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission

package object files {

  /**
    * Type alias for a file specific resource.
    */
  type FileResource = ResourceF[File]

  /**
    * File schemas
    */
  object schemas {
    val files: Iri = nxvSchema + "files.json"
  }

  /**
    * File vocabulary
    */
  val nxvFile: Iri = nxv + "File"

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("files/write")
  }

  /**
    * File contexts
    */
  object contexts {
    val files: Iri = nxvContexts + "files.json"
  }
}
