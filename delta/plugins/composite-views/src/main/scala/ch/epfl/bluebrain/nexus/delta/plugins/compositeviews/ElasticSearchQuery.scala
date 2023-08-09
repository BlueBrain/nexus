package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ViewIsDeprecated, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewElasticSearchProjectionResource, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import monix.bio.IO

trait ElasticSearchQuery {

  /**
    * Queries the Elasticsearch index of the passed composite views' projection. We check for the caller to have the
    * necessary query permissions on the views' projections before performing the query.
    *
    * @param id
    *   the id of the composite view either in Iri or aliased form
    * @param projectionId
    *   the id of the composite views' target projection either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the elasticsearch query to run
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def query(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[CompositeViewRejection, Json]

  /**
    * Queries all the Elasticsearch indices of the passed composite views' projection. We check for the caller to have
    * the necessary query permissions on the views' projections before performing the query.
    *
    * @param id
    *   the id of the composite view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the elasticsearch query to run
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def queryProjections(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[CompositeViewRejection, Json]

}

object ElasticSearchQuery {

  private[compositeviews] type ElasticSearchClientQuery =
    (JsonObject, Set[String], Query) => HttpResult[Json]
  private[compositeviews] type FetchView                =
    (IdSegmentRef, ProjectRef) => IO[CompositeViewRejection, ViewResource]
  private[compositeviews] type FetchProjection          =
    (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewElasticSearchProjectionResource]

  final def apply(
      aclCheck: AclCheck,
      views: CompositeViews,
      client: ElasticSearchClient,
      prefix: String
  ): ElasticSearchQuery =
    apply(aclCheck, views.fetch, views.fetchElasticSearchProjection, client.search(_, _, _)(SortList.empty), prefix)

  private[compositeviews] def apply(
      aclCheck: AclCheck,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      elasticSearchQuery: ElasticSearchClientQuery,
      prefix: String
  ): ElasticSearchQuery =
    new ElasticSearchQuery {

      override def query(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: JsonObject,
          qp: Uri.Query
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        for {
          viewRes           <- fetchProjection(id, projectionId, project)
          _                 <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          (view, projection) = viewRes.value
          _                 <- aclCheck.authorizeForOr(project, projection.permission)(AuthorizationFailed)
          index              = projectionIndex(projection, view.uuid, viewRes.rev, prefix).value
          search            <- elasticSearchQuery(query, Set(index), qp).mapError(WrappedElasticSearchClientError)
        } yield search

      override def queryProjections(
          id: IdSegment,
          project: ProjectRef,
          query: JsonObject,
          qp: Uri.Query
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        for {
          viewRes <- fetchView(id, project)
          _       <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          view     = viewRes.value
          indices <- allowedProjections(view, viewRes.rev, project)
          search  <- elasticSearchQuery(query, indices, qp).mapError(WrappedElasticSearchClientError)
        } yield search

      private def allowedProjections(
          view: CompositeView,
          rev: Int,
          project: ProjectRef
      )(implicit caller: Caller): IO[AuthorizationFailed, Set[String]] =
        aclCheck
          .mapFilterAtAddress[ElasticSearchProjection, String](
            view.elasticSearchProjections,
            project,
            p => p.permission,
            p => projectionIndex(p, view.uuid, rev, prefix).value
          )
          .tapEval { indices => IO.raiseWhen(indices.isEmpty)(AuthorizationFailed) }
    }
}
