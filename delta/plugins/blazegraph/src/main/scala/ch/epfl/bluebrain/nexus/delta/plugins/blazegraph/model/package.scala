package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest

package object model {

  /**
    * Type alias for a view specific resource.
    */
  type ViewResource = ResourceF[BlazegraphView]

  /**
    * Type alias for a indexing view specific resource.
    */
  type IndexingViewResource = ResourceF[IndexingBlazegraphView]

  /**
    * The fixed virtual schema of a BlazegraphView.
    */
  final val schema: ResourceRef = Latest(schemas + "views.json")

  /**
    * Blazegraph views contexts.
    */
  object contexts {
    val blazegraph: Iri         = nxvContexts + "sparql.json"
    val blazegraphMetadata: Iri = nxvContexts + "sparql-metadata.json"
  }

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("views/write")
    final val query: Permission = Permission.unsafe("views/query")
  }

  /**
    * The id for the default blazegraph view
    */
  final val defaultViewId = nxv + "defaultSparqlIndex"
}
