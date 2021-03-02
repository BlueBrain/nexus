package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.Aggregate

package object model {

  /**
    * Type alias for an archive resource.
    */
  type ArchiveResource = ResourceF[Archive]

  /**
    * Type alias for the aggregate specific to archives.
    */
  type ArchiveAggregate = Aggregate[
    String,
    ArchiveState,
    CreateArchive,
    ArchiveCreated,
    ArchiveRejection
  ]

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

    /**
      * The default archive context.
      */
    final val archives: Iri = iri"https://bluebrain.github.io/nexus/contexts/archives.json"
  }
}
