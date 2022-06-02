package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import monix.bio.IO

/**
  * Lifecycle hook for organization and project initialization. It's meant to be used for plugins to preconfigure an
  * organization or project, like for example the creation of a default view or setting the appropriate permissions.
  * Implementations should use a `many[ScopeInitialization]` binding such that all implementation are collected during
  * the service bootstrapping.
  */
trait ScopeInitialization {

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
  def onOrganizationCreation(organization: Organization, subject: Subject): IO[ScopeInitializationFailed, Unit]

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
  def onProjectCreation(project: Project, subject: Subject): IO[ScopeInitializationFailed, Unit]

}
