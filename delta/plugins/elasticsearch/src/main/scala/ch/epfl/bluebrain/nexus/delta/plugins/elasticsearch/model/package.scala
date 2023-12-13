package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest

package object model {

  /**
    * Type alias for a view specific resource.
    */
  type ViewResource = ResourceF[ElasticSearchView]

  /**
    * The fixed virtual schema of an ElasticSearchView.
    */
  final val schema: ResourceRef = Latest(schemas + "views.json")

  /**
    * ElasticSearch views contexts.
    */
  object contexts {
    val aggregations          = nxvContexts + "aggregations.json"
    val elasticsearch         = nxvContexts + "elasticsearch.json"
    val elasticsearchMetadata = nxvContexts + "elasticsearch-metadata.json"
    val elasticsearchIndexing = nxvContexts + "elasticsearch-indexing.json"
    val indexingMetadata      = nxvContexts + "indexing-metadata.json"
    val searchMetadata        = nxvContexts + "search-metadata.json"
  }

  object permissions {
    val write: Permission = Permission.unsafe("views/write")
    val read: Permission  = Permissions.resources.read
    val query: Permission = Permission.unsafe("views/query")
  }

  /**
    * The id for the default elasticsearch view
    */
  final val defaultViewId: Iri = nxv + "defaultElasticSearchIndex"
}
