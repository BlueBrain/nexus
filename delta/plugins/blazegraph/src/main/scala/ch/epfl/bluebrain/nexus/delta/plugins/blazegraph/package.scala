package ch.epfl.bluebrain.nexus.delta.plugins

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}

package object blazegraph {

  /**
    * Type alias for a view specific resource.
    */
  type BlazegraphViewResource = ResourceF[BlazegraphView]

  /**
    * The fixed virtual schema of a BlazegraphView.
    */
  final val schema: ResourceRef = Latest(schemas + "blazegraphview.json")

  /**
    * Blazegraph views contexts.
    */
  object contexts {
    val blazegraph = iri"https://bluebrain.github.io/nexus/contexts/blazegraph.json"
  }
}
