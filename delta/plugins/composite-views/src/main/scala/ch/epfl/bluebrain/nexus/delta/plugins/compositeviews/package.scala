package ch.epfl.bluebrain.nexus.delta.plugins

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

package object compositeviews {

  type FetchView = (IdSegmentRef, ProjectRef) => IO[CompositeViewRejection, ActiveViewDef]
  type ExpandId  = (IdSegmentRef, ProjectRef) => IO[CompositeViewRejection, Iri]

}
