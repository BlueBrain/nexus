package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ViewIsDeprecated, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewResource, ViewSparqlProjectionResource}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

trait BlazegraphQuery {

  /**
    * Queries the blazegraph common namespace of the passed composite view We check for the caller to have the necessary
    * query permissions on all the views' projections before performing the query.
    *
    * @param id
    *   the id of the composite view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the sparql query to run
    * @param responseType
    *   the desired response type
    */
  def query[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection. We check for the caller to have the
    * necessary query permissions on the views' projections before performing the query.
    *
    * @param id
    *   the id of the composite view either in Iri or aliased form
    * @param projectionId
    *   the id of the composite views' target projection either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the sparql query to run
    * @param responseType
    *   the desired response type
    */
  def query[R <: SparqlQueryResponse](
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection We check for the caller to have
    * the necessary query permissions on the views' projections before performing the query.
    *
    * @param id
    *   the id of the composite view either in Iri or aliased form
    * @param project
    *   the project where the view exists
    * @param query
    *   the sparql query to run
    * @param responseType
    *   the desired response type
    */
  def queryProjections[R <: SparqlQueryResponse](
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  )(implicit caller: Caller): IO[CompositeViewRejection, R]

}

object BlazegraphQuery {

  private[compositeviews] type FetchView       =
    (IdSegmentRef, ProjectRef) => IO[CompositeViewRejection, ViewResource]
  private[compositeviews] type FetchProjection =
    (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewSparqlProjectionResource]

  final def apply(
      aclCheck: AclCheck,
      views: CompositeViews,
      client: SparqlClient,
      prefix: String
  ): BlazegraphQuery =
    BlazegraphQuery(
      aclCheck,
      views.fetch,
      views.fetchBlazegraphProjection,
      client,
      prefix
    )

  private[compositeviews] def apply(
      aclCheck: AclCheck,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      client: SparqlQueryClient,
      prefix: String
  ): BlazegraphQuery =
    new BlazegraphQuery {

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[CompositeViewRejection, R] =
        for {
          viewRes    <- fetchView(id, project)
          _          <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          permissions = viewRes.value.projections.value.map(_.permission)
          _          <- aclCheck.authorizeForEveryOr(project, permissions)(AuthorizationFailed)
          namespace   = BlazegraphViews.namespace(viewRes.value.uuid, viewRes.rev.toInt, prefix)
          result     <- client.query(Set(namespace), query, responseType).mapError(WrappedBlazegraphClientError)
        } yield result

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[CompositeViewRejection, R] =
        for {
          viewRes           <- fetchProjection(id, projectionId, project)
          _                 <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          (view, projection) = viewRes.value
          _                 <- aclCheck.authorizeForOr(project, projection.permission)(AuthorizationFailed)
          namespace          = CompositeViews.namespace(projection, view, viewRes.rev.toInt, prefix)
          result            <- client.query(Set(namespace), query, responseType).mapError(WrappedBlazegraphClientError)
        } yield result

      override def queryProjections[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[CompositeViewRejection, R] =
        for {
          viewRes    <- fetchView(id, project)
          _          <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          view        = viewRes.value
          namespaces <- allowedProjections(view, viewRes.rev, project)
          result     <- client.query(namespaces, query, responseType).mapError(WrappedBlazegraphClientError)
        } yield result

      private def allowedProjections(
          view: CompositeView,
          rev: Long,
          project: ProjectRef
      )(implicit caller: Caller): IO[AuthorizationFailed, Set[String]] =
        aclCheck
          .mapFilterAtAddress[SparqlProjection, String](
            view.projections.value.collect { case p: SparqlProjection => p },
            project,
            p => p.permission,
            p => CompositeViews.namespace(p, view, rev.toInt, prefix)
          )
          .tapEval { namespaces => IO.raiseWhen(namespaces.isEmpty)(AuthorizationFailed) }
    }
}
