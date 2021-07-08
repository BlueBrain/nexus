package ch.epfl.bluebrain.nexus.delta.plugins

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts}

package object statistics {
  object contexts {
    val relationships = nxvContexts + "relationships.json"
    val properties    = nxvContexts + "properties.json"
  }
}
