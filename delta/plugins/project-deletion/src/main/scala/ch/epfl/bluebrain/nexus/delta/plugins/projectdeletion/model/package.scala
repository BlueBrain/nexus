package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts}

package object model {
  object contexts {
    val projectDeletion: Iri = nxvContexts + "project-deletion.json"
  }
}
