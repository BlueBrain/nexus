package ai.senscience.nexus.delta.projectdeletion

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts as nxvContexts

package object model {
  object contexts {
    val projectDeletion: Iri = nxvContexts + "project-deletion.json"
  }
}
