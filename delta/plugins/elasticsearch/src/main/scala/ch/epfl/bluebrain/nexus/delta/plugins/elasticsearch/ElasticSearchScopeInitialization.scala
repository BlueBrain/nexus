package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{ViewAlreadyExists, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultElasticsearchMapping, defaultElasticsearchSettings, defaultViewId}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{MigrationState, ScopeInitialization}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default ElasticSearchView as part of the project initialization. It performs a noop if
  * executed during a migration.
  *
  * @param views          the ElasticSearchViews module
  * @param serviceAccount the subject that will be recorded when performing the initialization
  */
class ElasticSearchScopeInitialization(views: ElasticSearchViews, serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger          = Logger[ElasticSearchScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private val defaultValue: IO[ScopeInitializationFailed, IndexingElasticSearchViewValue] = {
    for {
      mapping  <- defaultElasticsearchMapping
      settings <- defaultElasticsearchSettings
    } yield IndexingElasticSearchViewValue(
      mapping = mapping,
      settings = settings,
      includeMetadata = true,
      includeDeprecated = true,
      sourceAsText = true
    )
  }.memoize

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    if (MigrationState.isRunning) UIO.unit
    else
      defaultValue
        .flatMap { value =>
          views
            .create(defaultViewId, project.ref, value)
            .void
            .onErrorHandleWith {
              case _: ViewAlreadyExists            => UIO.unit // nothing to do, view already exits
              case _: WrappedProjectRejection      => UIO.unit // project is likely deprecated
              case _: WrappedOrganizationRejection => UIO.unit // org is likely deprecated
              case rej                             =>
                val str =
                  s"Failed to create the default ElasticSearchView for project '${project.ref}' due to '${rej.reason}'."
                UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
            }
        }
        .named("createDefaultElasticSearchView", ElasticSearchViews.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
