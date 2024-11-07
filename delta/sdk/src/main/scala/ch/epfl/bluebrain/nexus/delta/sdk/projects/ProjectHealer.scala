package ch.epfl.bluebrain.nexus.delta.sdk.projects

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait ProjectHealer {

  /**
    * Heal the project
    */
  def heal(project: ProjectRef): IO[Unit]

}

object ProjectHealer {

  def apply(
      errorStore: ScopeInitializationErrorStore,
      scopeInitializer: ScopeInitializer,
      serviceAccount: ServiceAccount
  ): ProjectHealer =
    new ProjectHealer {
      implicit private val serviceAccountSubject: Subject = serviceAccount.subject

      override def heal(project: ProjectRef): IO[Unit] =
        scopeInitializer.initializeProject(project) >> errorStore.delete(project)

    }

}
