package ch.epfl.bluebrain.nexus.delta.sdk
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity}

/**
  * Simple implementation that records created orgs and projects
  */
final class FailingScopeInitializationLog extends ScopeInitialization {

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[Unit] =
    IO.raiseError(ScopeInitializationFailed("failed at org creation"))
  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[Unit] =
    IO.raiseError(ScopeInitializationFailed("failed at project creation"))

  override def entityType: EntityType = EntityType("failingScopeInitializationLog")
}
