package ch.epfl.bluebrain.nexus.delta.plugins

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts}

package object statistics {
  object contexts {
    val relationships: Iri = nxvContexts + "relationships.json"
    val properties: Iri    = nxvContexts + "properties.json"
  }
}
