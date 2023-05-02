package ch.epfl.bluebrain.nexus.delta.sdk.projects

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectIsReferenced
import ch.epfl.bluebrain.nexus.delta.sourcing.EntityDependencyStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityDependency.ReferencedBy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.{IO, UIO}

/**
  * Allows to find reference of a project in other projects
  */
trait ProjectReferenceFinder {

  /**
    * Check if a given project is not referenced by another one
    * @param project
    *   the project to check
    */
  def raiseIfAny(project: ProjectRef): IO[ProjectIsReferenced, Unit]
}

object ProjectReferenceFinder {

  def apply(xas: Transactors): ProjectReferenceFinder =
    apply(EntityDependencyStore.directExternalReferences(_, xas))

  def apply(fetchReferences: ProjectRef => UIO[Set[ReferencedBy]]): ProjectReferenceFinder =
    (project: ProjectRef) =>
      fetchReferences(project).flatMap { references =>
        IO.raiseWhen(references.nonEmpty)(ProjectIsReferenced(project, references))
      }

}
