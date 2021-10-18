package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, ViewIsDeprecated, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewResource, ViewSparqlProjectionResource}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
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
      acls: Acls,
      views: CompositeViews,
      client: SparqlClient
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
    BlazegraphQuery(
      acls,
      views.fetch,
      views.fetchBlazegraphProjection,
      client: SparqlQueryClient
    )

  private[compositeviews] def apply(
      acls: Acls,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      client: SparqlQueryClient
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
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
          _          <- acls.authorizeForEveryOr(project, permissions)(AuthorizationFailed)
          namespace   = BlazegraphViews.namespace(viewRes.value.uuid, viewRes.rev, config)
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
          _                 <- acls.authorizeForOr(project, projection.permission)(AuthorizationFailed)
          namespace          = CompositeViews.namespace(projection, view, viewRes.rev, config.prefix)
          result            <- client.query(Set(namespace), query, responseType).mapError(WrappedBlazegraphClientError)
        } yield result

      override def queryProjections[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[CompositeViewRejection, R] =
        for {
          viewRes     <- fetchView(id, project)
          _           <- IO.raiseWhen(viewRes.deprecated)(ViewIsDeprecated(viewRes.id))
          view         = viewRes.value
          projections <- allowedProjections(view, project)
          namespaces   = projections.map(p => CompositeViews.namespace(p, view, viewRes.rev, config.prefix)).toSet
          result      <- client.query(namespaces, query, responseType).mapError(WrappedBlazegraphClientError)
        } yield result

      private def allowedProjections(
          view: CompositeView,
          project: ProjectRef
      )(implicit caller: Caller): IO[AuthorizationFailed, Seq[SparqlProjection]] = {
        val projections = view.projections.value.collect { case p: SparqlProjection => p }
        IO.traverse(projections)(p => acls.authorizeFor(project, p.permission).map(p -> _))
          .map(authorizations => authorizations.collect { case (p, true) => p })
          .flatMap(projections => IO.raiseWhen(projections.isEmpty)(AuthorizationFailed).as(projections))
      }
    }
}
