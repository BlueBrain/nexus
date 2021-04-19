package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

package object model {
  final val schema: ResourceRef = Latest(schemas + "view.json")
  final val compositeViewType   = nxv + "CompositeView"
  type ViewResource                        = ResourceF[CompositeView]
  type ViewProjectionResource              = ResourceF[(CompositeView, CompositeViewProjection)]
  type ViewElasticSearchProjectionResource = ResourceF[(CompositeView, ElasticSearchProjection)]
  type ViewSparqlProjectionResource        = ResourceF[(CompositeView, SparqlProjection)]

  /**
    * Composite views contexts.
    */
  object contexts {
    val compositeViews         = iri"https://bluebrain.github.io/nexus/contexts/composite-views.json"
    val compositeViewsMetadata = iri"https://bluebrain.github.io/nexus/contexts/composite-views-metadata.json"
  }

  object permissions {
    val write: Permission = Permission.unsafe("views/write")
    val read: Permission  = Permissions.resources.read
    val query: Permission = Permission.unsafe("views/query")
  }
}
