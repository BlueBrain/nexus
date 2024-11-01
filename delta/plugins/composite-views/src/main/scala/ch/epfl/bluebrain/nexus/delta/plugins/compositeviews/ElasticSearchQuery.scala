package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.projectionIndex
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.ElasticSearchProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.WrappedElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}

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
  )(implicit caller: Caller): IO[Json]

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
  )(implicit caller: Caller): IO[Json]

}

object ElasticSearchQuery {

  private[compositeviews] type ElasticSearchClientQuery =
    (JsonObject, Set[String], Query) => IO[Json]

  final def apply(
      aclCheck: AclCheck,
      views: CompositeViews,
      client: ElasticSearchClient,
      prefix: String
  ): ElasticSearchQuery =
    apply(aclCheck, views.fetchIndexingView, views.expand, client.search(_, _, _)(SortList.empty), prefix)

  private[compositeviews] def apply(
      aclCheck: AclCheck,
      fetchView: FetchView,
      expandId: ExpandId,
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
      )(implicit caller: Caller): IO[Json] =
        for {
          view       <- fetchView(id, project)
          projection <- fetchProjection(view, projectionId)
          _          <-
            aclCheck.authorizeForOr(project, projection.permission)(AuthorizationFailed(project, projection.permission))
          index       = projectionIndex(projection, view.uuid, prefix).value
          search     <- elasticSearchQuery(query, Set(index), qp).adaptError { case e: HttpClientError =>
                          WrappedElasticSearchClientError(e)
                        }
        } yield search

      override def queryProjections(
          id: IdSegment,
          project: ProjectRef,
          query: JsonObject,
          qp: Uri.Query
      )(implicit caller: Caller): IO[Json] =
        for {
          view    <- fetchView(id, project)
          indices <- allowedProjections(view, project)
          search  <- elasticSearchQuery(query, indices, qp).adaptError { case e: HttpClientError =>
                       WrappedElasticSearchClientError(e)
                     }
        } yield search

      private def fetchProjection(view: ActiveViewDef, projectionId: IdSegment) =
        expandId(projectionId, view.project).flatMap { id =>
          IO.fromEither(view.elasticsearchProjection(id))
        }

      private def allowedProjections(
          view: ActiveViewDef,
          project: ProjectRef
      )(implicit caller: Caller): IO[Set[String]] =
        aclCheck
          .mapFilterAtAddress[ElasticSearchProjection, String](
            view.elasticSearchProjections,
            project,
            p => p.permission,
            p => projectionIndex(p, view.uuid, prefix).value
          )
          .flatTap { indices =>
            IO.raiseWhen(indices.isEmpty)(AuthorizationFailed(s"No projection is accessible for view '${view.ref}'."))
          }
    }
}
