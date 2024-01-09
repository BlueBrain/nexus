package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitializer
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.projects.ScopeInitializationErrorStore
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.{ProjectHealingFailed, ProjectInitializationFailed}
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
        reinitializeProject(project) >> errorStore.delete(project)

      private def reinitializeProject(project: ProjectRef): IO[Unit] =
        scopeInitializer
          .initializeProject(project)
          .adaptError { case ProjectInitializationFailed(failure) => ProjectHealingFailed(failure, project) }
    }

}
