package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ResourceAlreadyExists
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.IndexingBlazegraphViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model._
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model._

/**
  * The default creation of the default SparqlView as part of the project initialization.
  *
  * @param views
  *   the BlazegraphViews module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  * @param defaults
  *   default name and description for the view
  */
class BlazegraphScopeInitialization(
    views: BlazegraphViews,
    serviceAccount: ServiceAccount,
    defaults: Defaults
) extends ScopeInitialization {

  private val logger                                        = Logger[BlazegraphScopeInitialization]
  implicit private val serviceAccountSubject: Subject       = serviceAccount.subject
  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(BlazegraphViews.entityType.value)

  private def defaultValue: IndexingBlazegraphViewValue = IndexingBlazegraphViewValue(
    name = Some(defaults.name),
    description = Some(defaults.description),
    resourceSchemas = IriFilter.None,
    resourceTypes = IriFilter.None,
    resourceTag = None,
    includeMetadata = true,
    includeDeprecated = true,
    permission = permissions.query
  )

  override def onProjectCreation(project: ProjectRef, subject: Identity.Subject): IO[Unit] =
    views
      .create(defaultViewId, project, defaultValue)
      .void
      .handleErrorWith {
        case _: ResourceAlreadyExists => IO.unit // nothing to do, view already exits
        case rej                      =>
          val str =
            s"Failed to create the default SparqlView for project '$project' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .span("createDefaultSparqlView")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[Unit] = IO.unit

  override def entityType: EntityType = BlazegraphViews.entityType

}
