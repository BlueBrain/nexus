package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{ResourceAlreadyExists, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.IndexingElasticSearchViewValue
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{defaultViewId, permissions}
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity, ServiceAccount}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.views.pipe.{SelectPredicates, SourceAsText}
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

/**
  * The default creation of the default ElasticSearchView as part of the project initialization.
  *
  * @param views
  *   the ElasticSearchViews module
  * @param serviceAccount
  *   the subject that will be recorded when performing the initialization
  */
class ElasticSearchScopeInitialization(views: ElasticSearchViews, serviceAccount: ServiceAccount)
    extends ScopeInitialization {

  private val logger: Logger          = Logger[ElasticSearchScopeInitialization]
  implicit private val caller: Caller = serviceAccount.caller

  private val defaultValue: IndexingElasticSearchViewValue =
    IndexingElasticSearchViewValue(
      resourceTag = None,
      List(SelectPredicates.defaultLabels, SourceAsText()),
      mapping = None,
      settings = None,
      context = None,
      permission = permissions.query
    )

  override def onProjectCreation(project: Project, subject: Identity.Subject): IO[ScopeInitializationFailed, Unit] =
    views
      .create(defaultViewId, project.ref, defaultValue)
      .void
      .onErrorHandleWith {
        case _: ResourceAlreadyExists        => UIO.unit // nothing to do, view already exits
        case _: WrappedProjectRejection      => UIO.unit // project is likely deprecated
        case _: WrappedOrganizationRejection => UIO.unit // org is likely deprecated
        case rej                             =>
          val str =
            s"Failed to create the default ElasticSearchView for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createDefaultElasticSearchView", ElasticSearchViews.moduleType)

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ScopeInitializationFailed, Unit] = IO.unit

}
