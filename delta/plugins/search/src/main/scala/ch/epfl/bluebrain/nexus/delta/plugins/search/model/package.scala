package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv

package object model {

  val defaultViewId: Iri       = nxv + "searchView"
  val defaultSourceId: Iri     = nxv + "searchSource"
  val defaultProjectionId: Iri = nxv + "searchProjection"
}
