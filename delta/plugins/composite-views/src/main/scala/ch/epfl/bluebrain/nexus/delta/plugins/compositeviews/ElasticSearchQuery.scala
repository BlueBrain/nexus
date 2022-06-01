package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ViewIsDeprecated, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewElasticSearchProjectionResource, ViewResource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
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
      acls: Acls,
      views: CompositeViews,
      client: ElasticSearchClient
  )(implicit config: ExternalIndexingConfig): ElasticSearchQuery =
    apply(acls, views.fetch, views.fetchElasticSearchProjection, client.search(_, _, _)(SortList.empty))

  private[compositeviews] def apply(
      acls: Acls,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      elasticSearchQuery: ElasticSearchClientQuery
  )(implicit config: ExternalIndexingConfig): ElasticSearchQuery =
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
          _                 <- acls.authorizeForOr(project, projection.permission)(AuthorizationFailed)
          index              = CompositeViews.index(projection, view, viewRes.rev, config.prefix).value
          search            <- elasticSearchQuery(query, Set(index), qp).mapError(WrappedElasticSearchClientError)
        } yield search

      override def queryProjections(
          id: IdSegment,
          project: ProjectRef,
          query: JsonObject,
          qp: Uri.Query
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        for {
          viewRes     <- fetchView(id, project)
          _           <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          view         = viewRes.value
          projections <- allowedProjections(view, project)
          indices      = projections.map(p => CompositeViews.index(p, view, viewRes.rev, config.prefix).value).toSet
          search      <- elasticSearchQuery(query, indices, qp).mapError(WrappedElasticSearchClientError)
        } yield search

      private def allowedProjections(
          view: CompositeView,
          project: ProjectRef
      )(implicit caller: Caller): IO[AuthorizationFailed, Seq[ElasticSearchProjection]] = {
        val projections = view.projections.value.collect { case p: ElasticSearchProjection => p }
        IO.traverse(projections)(p => acls.authorizeFor(project, p.permission).map(p -> _))
          .map(authorizations => authorizations.collect { case (p, true) => p })
          .flatMap(projections => IO.raiseWhen(projections.isEmpty)(AuthorizationFailed).as(projections))
      }
    }
}
