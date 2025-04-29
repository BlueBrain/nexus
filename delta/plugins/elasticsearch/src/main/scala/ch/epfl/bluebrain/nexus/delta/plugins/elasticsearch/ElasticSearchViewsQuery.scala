package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel, PointInTime}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.{DifferentElasticSearchViewType, ViewIsDeprecated, WrappedElasticSearchClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewValue.{AggregateElasticSearchViewValue, IndexingElasticSearchViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Project as ProjectAcl
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{View, ViewRef, ViewsStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.{Json, JsonObject}
import org.http4s.Query

import scala.concurrent.duration.FiniteDuration

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
      qp: Query
  )(implicit caller: Caller): IO[Json]

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
  def query(view: ViewRef, query: JsonObject, qp: Query)(implicit
      caller: Caller
  ): IO[Json] =
    this.query(view.viewId, view.project, query, qp)

  /**
    * Creates a point-in-time to be used in further searches
    *
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/point-in-time-api.html
    * @param id
    *   the target view
    * @param project
    *   project reference in which the view is
    * @param keepAlive
    *   extends the time to live of the corresponding point in time
    */
  def createPointInTime(id: IdSegment, project: ProjectRef, keepAlive: FiniteDuration)(implicit
      caller: Caller
  ): IO[PointInTime]

  /**
    * Deletes the given point-in-time
    *
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/point-in-time-api.html
    */
  def deletePointInTime(pointInTime: PointInTime)(implicit caller: Caller): IO[Unit]

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
  )(implicit caller: Caller): IO[Json]

}

/**
  * Operations that interact with the elasticsearch indices managed by ElasticSearchViews.
  */
final class ElasticSearchViewsQueryImpl private[elasticsearch] (
    viewStore: ViewsStore[ElasticSearchViewRejection],
    aclCheck: AclCheck,
    client: ElasticSearchClient
) extends ElasticSearchViewsQuery {

  override def query(
      id: IdSegment,
      project: ProjectRef,
      query: JsonObject,
      qp: Query
  )(implicit caller: Caller): IO[Json] = {
    for {
      view    <- viewStore.fetch(id, project)
      indices <- extractIndices(view)
      search  <- client
                   .search(query, indices, qp)(SortList.empty)
                   .adaptError { case e: ElasticSearchClientError =>
                     WrappedElasticSearchClientError(e)
                   }
    } yield search
  }

  private def extractIndices(view: View)(implicit c: Caller): IO[Set[String]] = view match {
    case v: IndexingView  =>
      aclCheck
        .authorizeForOr(v.ref.project, v.permission)(AuthorizationFailed(v.ref.project, v.permission))
        .as(Set(v.index))
    case v: AggregateView =>
      aclCheck.mapFilter[IndexingView, String](
        v.views,
        v => ProjectAcl(v.ref.project) -> v.permission,
        _.index
      )
  }

  override def mapping(
      id: IdSegment,
      project: ProjectRef
  )(implicit caller: Caller): IO[Json] =
    for {
      _       <- aclCheck.authorizeForOr(project, permissions.write)(AuthorizationFailed(project, permissions.write))
      view    <- viewStore.fetch(id, project)
      index   <- indexOrError(view, id)
      mapping <- client
                   .mapping(index)
                   .adaptError { case e: ElasticSearchClientError => WrappedElasticSearchClientError(e) }
    } yield mapping

  override def createPointInTime(id: IdSegment, project: ProjectRef, keepAlive: FiniteDuration)(implicit
      caller: Caller
  ): IO[PointInTime] =
    for {
      _     <- aclCheck.authorizeForOr(project, permissions.write)(AuthorizationFailed(project, permissions.write))
      view  <- viewStore.fetch(id, project)
      index <- indexOrError(view, id)
      pit   <- client
                 .createPointInTime(index, keepAlive)
                 .adaptError { case e: ElasticSearchClientError => WrappedElasticSearchClientError(e) }
    } yield pit

  override def deletePointInTime(pointInTime: PointInTime)(implicit caller: Caller): IO[Unit] =
    client
      .deletePointInTime(pointInTime)
      .adaptError { case e: ElasticSearchClientError => WrappedElasticSearchClientError(e) }

  private def indexOrError(view: View, id: IdSegment): IO[IndexLabel] = view match {
    case IndexingView(_, index, _) => IO.fromEither(IndexLabel(index))
    case _: AggregateView          =>
      IO.raiseError(
        DifferentElasticSearchViewType(
          id.toString,
          ElasticSearchViewType.AggregateElasticSearch,
          ElasticSearchViewType.ElasticSearch
        )
      )
  }

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
        ElasticSearchViewState.serializer,
        views.fetchState,
        view =>
          IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
            .as(viewIriOrIndexingView(prefix, view)),
        xas
      ),
      aclCheck,
      client
    )

  private def viewIriOrIndexingView(prefix: String, view: ElasticSearchViewState): Either[Iri, IndexingView] =
    view.value match {
      case _: AggregateElasticSearchViewValue => Left(view.id)
      case i: IndexingElasticSearchViewValue  =>
        IndexingView(
          ViewRef(view.project, view.id),
          ElasticSearchViews.index(view.uuid, view.indexingRev, prefix).value,
          i.permission
        ).asRight
    }
}
