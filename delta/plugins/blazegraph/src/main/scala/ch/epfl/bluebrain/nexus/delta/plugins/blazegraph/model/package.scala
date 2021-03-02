package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.resources
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}

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
  final val schema: ResourceRef = Latest(schemas + "view.json")

  /**
    * Blazegraph views contexts.
    */
  object contexts {
    val blazegraph = iri"https://bluebrain.github.io/nexus/contexts/blazegraph.json"
  }

  object permissions {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("views/write")
  }

  /**
    * The default IndexingBlazegraphView permission.
    */
  final val defaultPermission = Permission.unsafe("views/query")

  /**
    * The id for the default blazegraph view
    */
  final val defaultViewId = nxv + "defaultSparqlIndex"
}
