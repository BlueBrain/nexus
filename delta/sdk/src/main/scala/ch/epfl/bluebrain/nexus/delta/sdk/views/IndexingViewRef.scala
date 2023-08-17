package ch.epfl.bluebrain.nexus.delta.sdk.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * View reference for indexing purposes
  * @param ref
  *   the id and the project of the view
  * @param indexingRev
  *   the indexing revision
  */
final case class IndexingViewRef(ref: ViewRef, indexingRev: IndexingRev) {
  def project: ProjectRef = ref.project

  def id: Iri = ref.viewId
}

object IndexingViewRef {

  def apply(project: ProjectRef, id: Iri, indexingRev: IndexingRev): IndexingViewRef =
    IndexingViewRef(ViewRef(project, id), indexingRev)
}
