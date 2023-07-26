package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.permissions
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.{AuthorizationFailed, ElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.IndexingView
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import io.circe.JsonObject
import monix.bio.{IO, UIO}

/**
  * Allow to list resources from the default elasticsearch views
  */
trait DefaultViewsQuery[Result, Aggregate] {

  /**
    * Retrieves a list of resources from the provided search request
    */
  def list(searchRequest: DefaultSearchRequest)(implicit caller: Caller): IO[ElasticSearchQueryError, Result]

  /**
    * Retrieves aggregations for the provided search request
    */
  def aggregate(searchRequest: DefaultSearchRequest)(implicit caller: Caller): IO[ElasticSearchQueryError, Aggregate]
}

object DefaultViewsQuery {

  type Elasticsearch = DefaultViewsQuery[SearchResults[JsonObject], AggregationResult]

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
          .search(request.params, views.map(_.index), Uri.Query.Empty)(request.pagination, request.sort)
          .mapError(ElasticSearchClientError),
      (request: DefaultSearchRequest, views: Set[IndexingView]) =>
        client
          .aggregate(request.params, views.map(_.index), Uri.Query.Empty, config.listingBucketSize)
          .mapError(ElasticSearchClientError)
    )
  }

  def apply[Result, Aggregate](
      fetchViews: Scope => UIO[List[IndexingView]],
      aclCheck: AclCheck,
      listAction: (DefaultSearchRequest, Set[IndexingView]) => IO[ElasticSearchQueryError, Result],
      aggregateAction: (DefaultSearchRequest, Set[IndexingView]) => IO[ElasticSearchQueryError, Aggregate]
  ): DefaultViewsQuery[Result, Aggregate] = new DefaultViewsQuery[Result, Aggregate] {

    private def filterViews(scope: Scope)(implicit caller: Caller) =
      fetchViews(scope)
        .flatMap { allViews =>
          aclCheck.mapFilter[IndexingView, IndexingView](
            allViews,
            v => ProjectAcl(v.ref.project) -> permissions.read,
            identity
          )(caller)
        }
        .flatMap { views =>
          IO.raiseWhen(views.isEmpty)(AuthorizationFailed).as(views)
        }

    override def list(
        searchRequest: DefaultSearchRequest
    )(implicit caller: Caller): IO[ElasticSearchQueryError, Result] =
      filterViews(searchRequest.scope).flatMap { views =>
        listAction(searchRequest, views)
      }

    /**
      * Retrieves aggregations for from the provided search
      */
    override def aggregate(
        searchRequest: DefaultSearchRequest
    )(implicit caller: Caller): IO[ElasticSearchQueryError, Aggregate] =
      filterViews(searchRequest.scope).flatMap { views =>
        aggregateAction(searchRequest, views)
      }

  }
}
