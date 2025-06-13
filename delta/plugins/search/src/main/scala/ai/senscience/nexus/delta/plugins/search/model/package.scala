package ai.senscience.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings

package object model {

  val defaultViewId: Iri       = nxv + "searchView"
  val defaultSourceId: Iri     = nxv + "searchSource"
  val defaultProjectionId: Iri = nxv + "searchProjection"

  /**
    * The default Search API mappings
    */
  val defaulMappings: ApiMappings = ApiMappings("search" -> defaultViewId)
}
