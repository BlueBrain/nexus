package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.{ProjectContextRejection, ResourceAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default SparqlView as part of the project initialization.
  *
  * @param views
  *   the BlazegraphViews module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class BlazegraphScopeInitialization(views: BlazegraphViews, serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger                          = Logger[BlazegraphScopeInitialization]
  implicit private val serviceAccountSubject: Subject = serviceAccount.subject

  private val defaultValue: IndexingBlazegraphViewValue = IndexingBlazegraphViewValue(
    resourceSchemas = Set.empty,
    resourceTypes = Set.empty,
    resourceTag = None,
    includeMetadata = true,
    includeDeprecated = true,
    permission = permissions.query
  )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    views
      .create(defaultViewId, project.ref, defaultValue)
      .void
      .onErrorHandleWith {
        case _: ResourceAlreadyExists   => UIO.unit // nothing to do, view already exits
        case _: ProjectContextRejection => UIO.unit // project or org are likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the default SparqlView for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createDefaultSparqlView", BlazegraphViews.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
