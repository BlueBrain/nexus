package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlClientError, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewResource, ViewSparqlProjectionResource}
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import monix.bio.IO

trait BlazegraphQuery {

  /**
    * Queries the blazegraph common namespace of the passed composite view.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def query(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def query(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjections(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

}

object BlazegraphQuery {

  private[compositeviews] type BlazegraphClientQuery =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, SparqlResults]
  private[compositeviews] type FetchView             =
    (IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewResource]
  private[compositeviews] type FetchProjection       =
    (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewSparqlProjectionResource]

  final def apply(
      acls: Acls,
      views: CompositeViews,
      client: BlazegraphClient
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
    BlazegraphQuery(acls, views.fetch, views.fetchBlazegraphProjection, client.query)

  private[compositeviews] def apply(
      acls: Acls,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      blazegraphQuery: BlazegraphClientQuery
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
    new BlazegraphQuery {

      override def query(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        for {
          viewRes    <- fetchView(id, project)
          permissions = viewRes.value.projections.value.map(_.permission)
          _          <- acls.authorizeForEveryOr(project, permissions)(AuthorizationFailed)
          namespace   = BlazegraphViews.namespace(viewRes.value.uuid, viewRes.rev, config)
          search     <- blazegraphQuery(Set(namespace), query).mapError(WrappedBlazegraphClientError)
        } yield search

      override def query(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        for {
          viewRes           <- fetchProjection(id, projectionId, project)
          (view, projection) = viewRes.value
          _                 <- acls.authorizeForOr(project, projection.permission)(AuthorizationFailed)
          namespace          = CompositeViews.namespace(projection, view, viewRes.rev, config.prefix)
          search            <- blazegraphQuery(Set(namespace), query).mapError(WrappedBlazegraphClientError)
        } yield search

      override def queryProjections(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        for {
          viewRes     <- fetchView(id, project)
          view         = viewRes.value
          projections <- allowedProjections(view, project)
          namespaces   = projections.map(p => CompositeViews.namespace(p, view, viewRes.rev, config.prefix))
          search      <- blazegraphQuery(namespaces, query).mapError(WrappedBlazegraphClientError)
        } yield search

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
