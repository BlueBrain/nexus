package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{AuthorizationFailed, DifferentElasticSearchViewType, ViewIsDeprecated, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.{Project => ProjectAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{ViewRef, ViewsStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Allows operations on Elasticsearch views
  */
trait ElasticSearchViewsQuery {

  /**
    * Queries the elasticsearch index (or indices) managed by the view with the passed ''id''. We check for the caller
    * to have the necessary query permissions on the view before performing the query.
    *
    * @param id
    *   the id of the view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the elasticsearch query to run
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json]

  /**
    * Queries the elasticsearch index (or indices) managed by the view. We check for the caller to have the necessary
    * query permissions on the view before performing the query.
    * @param view
    *   the reference to the view
    * @param query
    *   the elasticsearch query to run
    * @param qp
    *   the extra query parameters for the elasticsearch index
    */
  def query(view: ViewRef, query: JsonObject, qp: Uri.Query)(implicit
      caller: Caller
  ): IO[ElasticSearchViewRejection, Json] =
    this.query(view.viewId, view.project, query, qp)

  /**
    * Fetch the elasticsearch mapping of the provided view
    * @param id
    *   id of the view for which to fetch the mapping
    * @param project
    *   project reference in which the view is
    */
  def mapping(
      id: IdSegment,
      project: ProjectRef
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json]

}

/**
  * Operations that interact with the elasticsearch indices managed by ElasticSearchViews.
  */
final class ElasticSearchViewsQueryImpl private[elasticsearch] (
    viewStore: ViewsStore[ElasticSearchViewRejection],
    aclCheck: AclCheck,
    client: ElasticSearchClient
) extends ElasticSearchViewsQuery {

  def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Uri.Query
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] = {
    for {
      view    <- viewStore.fetch(id, project)
      indices <- view match {
                   case v: IndexingView  =>
                     aclCheck.authorizeForOr(v.ref.project, v.permission)(AuthorizationFailed).as(Set(v.index))
                   case v: AggregateView =>
                     aclCheck.mapFilter[IndexingView, String](
                       v.views,
                       v => ProjectAcl(v.ref.project) -> v.permission,
                       _.index
                     )
                 }
      search  <- client.search(query, indices, qp)(SortList.empty).mapError(WrappedElasticSearchClientError)
    } yield search
  }

  override def mapping(
      id: IdSegment,
      project: ProjectRef
  )(implicit caller: Caller): IO[ElasticSearchViewRejection, Json] =
    for {
      view   <- aclCheck.authorizeForOr(project, permissions.write)(AuthorizationFailed).as(viewStore.fetch(id, project))
      index  <- view.map {
                  case v: IndexingView  =>
                    IO.pure(v.index)
                  case _: AggregateView =>
                    IO.raiseError(
                      DifferentElasticSearchViewType(
                        iri"$id",
                        ElasticSearchViewType.AggregateElasticSearch,
                        ElasticSearchViewType.ElasticSearch
                      )
                    )
                }
      search <- index.flatMap { ind =>
                  client.mapping(IndexLabel.unsafe(ind)).mapError(WrappedElasticSearchClientError)
                }
    } yield search

}

object ElasticSearchViewsQuery {

  final def apply(
      aclCheck: AclCheck,
      views: ElasticSearchViews,
      client: ElasticSearchClient,
      prefix: String,
      xas: Transactors
  ): ElasticSearchViewsQuery =
    new ElasticSearchViewsQueryImpl(
      ViewsStore[ElasticSearchViewRejection, ElasticSearchViewState](
        ElasticSearchViews.entityType,
        ElasticSearchViewState.serializer,
        defaultViewId,
        views.fetchState(_, _).map(_._2),
        view =>
          IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id)).as {
            view.value match {
              case _: AggregateElasticSearchViewValue =>
                Left(view.id)
              case i: IndexingElasticSearchViewValue  =>
                Right(
                  IndexingView(
                    ViewRef(view.project, view.id),
                    ElasticSearchViews.index(view.uuid, view.indexingRev, prefix).value,
                    i.permission
                  )
                )
            }
          },
        xas
      ),
      aclCheck,
      client
    )
}
