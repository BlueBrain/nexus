package ch.epfl.bluebrain.nexus.delta.sdk.views

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

final case class ViewIndexingRef(ref: ViewRef, indexingRev: IndexingRev) {
  def project: ProjectRef = ref.project

  def id: Iri = ref.viewId
}

object ViewIndexingRef {

  def apply(project: ProjectRef, id: Iri, indexingRev: IndexingRev): ViewIndexingRef =
    ViewIndexingRef(ViewRef(project, id), indexingRev)
}
