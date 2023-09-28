package ch.epfl.bluebrain.nexus.delta.sdk.ce

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toMonixBIOOps
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import monix.bio.{IO => BIO}

/**
  * Lifecycle hook for organization and project initialization. It's meant to be used for plugins to preconfigure an
  * organization or project, like for example the creation of a default view or setting the appropriate permissions.
  * Implementations should use a `many[ScopeInitialization]` binding such that all implementation are collected during
  * the service bootstrapping.
  */
trait CatsScopeInitialization {

  /**
    * The method is invoked synchronously during the organization creation for its immediate configuration.
    * Additionally, in order to correct failures that may have occurred, this method will also be invoked as an
    * opportunity to heal as part of the organization event log replay during the bootstrapping of the service. The
    * method is expected to perform necessary checks such that the initialization would not be executed twice.
    *
    * @param organization
    *   the organization that was created
    * @param subject
    *   the identity that was recorded for the creation of the organization
    */
  def onOrganizationCreation(organization: Organization, subject: Subject): IO[Unit]

  /**
    * The method is invoked synchronously during the project creation for immediate configuration of the project.
    * Additionally, in order to correct failures that may have occurred, this method will also be invoked as an
    * opportunity to heal as part of the project event log replay during the bootstrapping of the service. The method is
    * expected to perform necessary checks such that the initialization would not be executed twice.
    *
    * @param project
    *   the project that was created
    * @param subject
    *   the identity that was recorded for the creation of the project
    */
  def onProjectCreation(project: Project, subject: Subject): IO[Unit]

}

object CatsScopeInitialization {

  /** CE Migration helper */
  def toBioScope(catsScope: CatsScopeInitialization): ScopeInitialization =
    new ScopeInitialization {
      override def onOrganizationCreation(
          organization: Organization,
          subject: Subject
      ): BIO[ServiceError.ScopeInitializationFailed, Unit] =
        catsScope.onOrganizationCreation(organization, subject).toBIO

      override def onProjectCreation(
          project: Project,
          subject: Subject
      ): BIO[ServiceError.ScopeInitializationFailed, Unit] =
        catsScope.onProjectCreation(project, subject).toBIO
    }

}
