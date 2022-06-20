package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.kernel.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjectionFields.ElasticSearchProjectionFields
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{ViewAlreadyExists, WrappedOrganizationRejection, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSourceFields.ProjectSourceFields
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IndexGroup
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchConfig.IndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.{defaultProjectionId, defaultSourceId, defaultViewId}
import ch.epfl.bluebrain.nexus.delta.sdk.ScopeInitialization
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.ScopeInitializationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

final class SearchScopeInitialization(
    views: CompositeViews,
    config: IndexingConfig,
    serviceAccount: ServiceAccount
)(implicit baseUri: BaseUri)
    extends ScopeInitialization {

  private val logger: Logger                          = Logger[SearchScopeInitialization]
  implicit private val serviceAccountSubject: Subject = serviceAccount.subject

  private val searchGroup = Some(IndexGroup.unsafe("search"))

  override def onProjectCreation(
      project: Project,
      subject: Identity.Subject
  ): IO[ServiceError.ScopeInitializationFailed, Unit] =
    views
      .create(
        defaultViewId,
        project.ref,
        CompositeViewFields(
          NonEmptySet.of(ProjectSourceFields(id = Some(defaultSourceId))),
          NonEmptySet.of(
            ElasticSearchProjectionFields(
              id = Some(defaultProjectionId),
              query = config.query,
              mapping = config.mapping,
              indexGroup = searchGroup,
              context = config.context,
              settings = config.settings,
              resourceTypes = config.resourceTypes
            )
          ),
          None
        )
      )
      .void
      .onErrorHandleWith {
        case _: ViewAlreadyExists            => UIO.unit // nothing to do, view already exits
        case _: WrappedProjectRejection      => UIO.unit // project is likely deprecated
        case _: WrappedOrganizationRejection => UIO.unit // org is likely deprecated
        case rej                             =>
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
