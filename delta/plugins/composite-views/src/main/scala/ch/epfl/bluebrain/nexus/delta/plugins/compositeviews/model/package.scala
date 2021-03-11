package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

package object model {
  final val schema: ResourceRef = Latest(schemas + "view.json")
  final val compositeViewType   = nxv + "CompositeView"
  type ViewResource = ResourceF[CompositeView]

  /**
    * ElasticSearch views contexts.
    */
  object contexts {
    val compositeView         = iri"https://bluebrain.github.io/nexus/contexts/elasticsearch.json"
    val compositeViewIndexing = iri"https://bluebrain.github.io/nexus/contexts/elasticsearch-indexing.json"
  }

  object permissions {
    val write: Permission = Permission.unsafe("views/write")
    val read: Permission  = Permissions.resources.read
    val query: Permission = Permission.unsafe("views/query")
  }
}
