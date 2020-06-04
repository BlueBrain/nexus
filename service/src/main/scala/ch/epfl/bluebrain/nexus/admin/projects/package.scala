package ch.epfl.bluebrain.nexus.admin

import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.sourcing.Aggregate

package object projects {
  val types: Set[AbsoluteIri] = Set(nxv.Project.value)

  type Agg[F[_]] = Aggregate[F, String, ProjectEvent, ProjectState, ProjectCommand, ProjectRejection]

  type ProjectResource            = ResourceF[Project]
  type EventOrRejection           = Either[ProjectRejection, ProjectEvent]
  type StateOrRejection           = Either[ProjectRejection, ProjectState.Current]
  type ProjectResourceOrRejection = Either[ProjectRejection, ProjectResource]
  type ProjectMetaOrRejection     = Either[ProjectRejection, ResourceF[Unit]]

}
