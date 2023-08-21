package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ProjectContextRejection, ViewAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

final class SearchScopeInitialization(
    views: CompositeViews,
    config: IndexingConfig,
    serviceAccount: ServiceAccount,
    defaults: Defaults
)(implicit baseUri: BaseUri)
    extends ScopeInitialization {

  private val logger: Logger                          = Logger[SearchScopeInitialization]
  implicit private val serviceAccountSubject: Subject = serviceAccount.subject

  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[ServiceError.ScopeInitializationFailed, Unit] =
    views
      .create(
        defaultViewId,
        project.ref,
        SearchViewFactory(defaults, config)
      )
      .void
      .onErrorHandleWith {
        case _: ViewAlreadyExists       => UIO.unit // nothing to do, view already exits
        case _: ProjectContextRejection => UIO.unit // project or org are likely deprecated
        case rej                        =>
          val str =
            s"Failed to create the search view for project '${project.ref}' due to '${rej.reason}'."
          UIO.delay(logger.error(str)) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createSearchView", "search")

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[ServiceError.ScopeInitializationFailed, Unit] = IO.unit
}
