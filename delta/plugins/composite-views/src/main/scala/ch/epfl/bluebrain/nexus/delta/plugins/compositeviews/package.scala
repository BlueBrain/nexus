package ch.epfl.bluebrain.nexus.delta.plugins

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegmentRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

package object compositeviews {

  type FetchView = (IdSegmentRef, ProjectRef) => IO[ActiveViewDef]
  type ExpandId  = (IdSegmentRef, ProjectRef) => IO[Iri]

}
