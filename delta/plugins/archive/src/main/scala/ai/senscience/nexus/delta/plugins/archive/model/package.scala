package ai.senscience.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts as nxvContexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest

package object model {

  /**
    * Type alias for an archive resource.
    */
  type ArchiveResource = ResourceF[Archive]

  /**
    * The fixed virtual schema of an Archive.
    */
  final val schema: ResourceRef = Latest(schemas + "archives.json")

  /**
    * The archive type.
    */
  final val tpe: Iri = nxv + "Archive"

  /**
    * Archive contexts.
    */
  object contexts {
    final val archives: Iri         = nxvContexts + "archives.json"
    final val archivesMetadata: Iri = nxvContexts + "archives-metadata.json"
  }

  /**
    * Archive permissions.
    */
  object permissions {
    final val read: Permission  = Permissions.resources.read
    final val write: Permission = Permissions.resources.read
  }
}
