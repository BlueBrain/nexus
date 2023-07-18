package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectDeletionIsDisabled, ProjectIsReferenced}
import ch.epfl.bluebrain.nexus.delta.sourcing.{EntityDependencyStore, Transactors}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.ReferencedBy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

/**
  * Validate if a project can be deleted
  */
trait ValidateProjectDeletion {

  def apply(project: ProjectRef): IO[ProjectRejection, Unit]
}

object ValidateProjectDeletion {

  def apply(xas: Transactors, enabled: Boolean): ValidateProjectDeletion =
    apply(EntityDependencyStore.directExternalReferences(_, xas), enabled)

  def apply(fetchReferences: ProjectRef => UIO[Set[ReferencedBy]], enabled: Boolean): ValidateProjectDeletion =
    (project: ProjectRef) =>
      IO.raiseWhen(!enabled)(ProjectDeletionIsDisabled) >>
        fetchReferences(project).flatMap { references =>
          IO.raiseWhen(references.nonEmpty)(ProjectIsReferenced(project, references))
        }

}
