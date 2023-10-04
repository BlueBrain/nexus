package ch.epfl.bluebrain.nexus.delta.plugins.search

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ProjectContextRejection, ViewAlreadyExists}
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.defaultViewId
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.{Defaults, ScopeInitialization}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

final class SearchScopeInitialization(
    views: CompositeViews,
    config: IndexingConfig,
    serviceAccount: ServiceAccount,
    defaults: Defaults
)(implicit baseUri: BaseUri)
    extends ScopeInitialization {

  private val logger = Logger.cats[SearchScopeInitialization]

  implicit private val serviceAccountSubject: Subject = serviceAccount.subject

  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[Unit] = {
    views
      .create(defaultViewId, project.ref, SearchViewFactory(defaults, config))
      .void
      .handleErrorWith {
        case _: ViewAlreadyExists       => IO.unit
        case _: ProjectContextRejection => IO.unit
        case rej                        =>
          val str =
            s"Failed to create the search view for project '${project.ref}' due to '${rej.getMessage}'."
          logger.error(str) >> IO.raiseError(ScopeInitializationFailed(str))
      }
      .named("createSearchView", "search")
  }

  override def onOrganizationCreation(
      organization: Organization,
      subject: Identity.Subject
  ): IO[Unit] = IO.unit
}
