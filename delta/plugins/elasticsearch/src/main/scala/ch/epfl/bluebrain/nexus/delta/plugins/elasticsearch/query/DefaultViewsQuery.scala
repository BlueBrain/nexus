package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.{DefaultViewNotFound, ElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import io.circe.JsonObject

/**
  * Allow to list resources from the default elasticsearch views
  */
trait DefaultViewsQuery[Result, Aggregate] {

  /**
    * Retrieves a list of resources from the provided search request
    */
  def list(searchRequest: DefaultSearchRequest)(implicit caller: Caller): IO[Result]

  /**
    * Retrieves aggregations for the provided search request
    */
  def aggregate(searchRequest: DefaultSearchRequest)(implicit caller: Caller): IO[Aggregate]
}

object DefaultViewsQuery {

  type Elasticsearch = DefaultViewsQuery[SearchResults[JsonObject], AggregationResult]

  private val excludeOriginalSource = "_source_excludes" -> "_original_source"

  def apply(
      aclCheck: AclCheck,
      client: ElasticSearchClient,
      config: ElasticSearchViewsConfig,
      prefix: String,
      xas: Transactors
  )(implicit baseUri: BaseUri): DefaultViewsQuery.Elasticsearch = {
    val defaultViewsStore = DefaultViewsStore(prefix, xas)
    apply(
      defaultViewsStore.find,
      aclCheck,
      (request: DefaultSearchRequest, views: Set[IndexingView]) =>
        client
          .search(request.params, views.map(_.index), Uri.Query(excludeOriginalSource))(
            request.pagination,
            request.sort
          )
          .adaptError { case e: HttpClientError => ElasticSearchClientError(e) },
      (request: DefaultSearchRequest, views: Set[IndexingView]) =>
        client
          .aggregate(request.params, views.map(_.index), Uri.Query.Empty, config.listingBucketSize)
          .adaptError { case e: HttpClientError => ElasticSearchClientError(e) }
    )
  }

  def apply[Result, Aggregate](
      fetchViews: Scope => IO[List[IndexingView]],
      aclCheck: AclCheck,
      listAction: (DefaultSearchRequest, Set[IndexingView]) => IO[Result],
      aggregateAction: (DefaultSearchRequest, Set[IndexingView]) => IO[Aggregate]
  ): DefaultViewsQuery[Result, Aggregate] = new DefaultViewsQuery[Result, Aggregate] {

    private def filterViews(scope: Scope)(implicit caller: Caller) =
      fetchViews(scope)
        .flatMap { allViews =>
          val authorizedViews = aclCheck
            .mapFilter[IndexingView, IndexingView](
              allViews,
              v => ProjectAcl(v.ref.project) -> permissions.read,
              identity
            )(caller)

          scope match {
            case Scope.Project(ref) =>
              (canReadProject(ref), authorizedViews).flatMapN {
                case (false, _)                     => raiseNoViewAccessibleError
                case (true, views) if views.isEmpty => raiseDefaultViewError(ref)
                case (_, views)                     => IO.pure(views)
              }
            case _                  =>
              authorizedViews.flatMap {
                case views if views.isEmpty => raiseNoViewAccessibleError
                case views                  => IO.pure(views)
              }
          }
        }

    override def list(
        searchRequest: DefaultSearchRequest
    )(implicit caller: Caller): IO[Result] =
      filterViews(searchRequest.scope).flatMap { views =>
        listAction(searchRequest, views)
      }

    /**
      * Retrieves aggregations for from the provided search
      */
    override def aggregate(
        searchRequest: DefaultSearchRequest
    )(implicit caller: Caller): IO[Aggregate] =
      filterViews(searchRequest.scope).flatMap { views =>
        aggregateAction(searchRequest, views)
      }

    private def canReadProject(ref: ProjectRef)(implicit caller: Caller) =
      aclCheck.authorizeFor(ProjectAcl(ref), permissions.read)

    private def raiseNoViewAccessibleError: IO[Set[IndexingView]] =
      IO.raiseError(AuthorizationFailed("No views are accessible."))

    private def raiseDefaultViewError(ref: ProjectRef): IO[Set[IndexingView]] =
      IO.raiseError(DefaultViewNotFound(ref))

  }
}
