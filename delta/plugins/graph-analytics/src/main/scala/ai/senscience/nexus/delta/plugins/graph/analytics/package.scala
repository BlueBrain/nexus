package ai.senscience.nexus.delta.plugins.graph

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts as nxvContexts
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission

package object analytics {
  object contexts {
    val relationships: Iri = nxvContexts + "relationships.json"
    val properties: Iri    = nxvContexts + "properties.json"
  }

  object permissions {
    final val query: Permission = Permission.unsafe("views/query")
  }
}
