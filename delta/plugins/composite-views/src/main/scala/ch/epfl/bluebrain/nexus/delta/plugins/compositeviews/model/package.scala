package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts => nxvContexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest

package object model {
  final val schema: ResourceRef = Latest(schemas + "views.json")
  final val compositeViewType   = nxv + "CompositeView"
  type ViewResource                        = ResourceF[CompositeView]
  type ViewProjectionResource              = ResourceF[(CompositeView, CompositeViewProjection)]
  type ViewSourceResource                  = ResourceF[(CompositeView, CompositeViewSource)]
  type ViewElasticSearchProjectionResource = ResourceF[(CompositeView, ElasticSearchProjection)]
  type ViewSparqlProjectionResource        = ResourceF[(CompositeView, SparqlProjection)]

  /**
    * Composite views contexts.
    */
  object contexts {
    val compositeViews         = nxvContexts + "composite-views.json"
    val compositeViewsMetadata = nxvContexts + "composite-views-metadata.json"
  }

  object permissions {
    val write: Permission = Permission.unsafe("views/write")
    val read: Permission  = Permissions.resources.read
    val query: Permission = Permission.unsafe("views/query")
  }
}
