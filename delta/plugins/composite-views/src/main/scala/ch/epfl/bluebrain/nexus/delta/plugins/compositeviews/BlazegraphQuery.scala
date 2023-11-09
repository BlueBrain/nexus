package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{commonNamespace, projectionNamespace}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.WrappedBlazegraphClientError
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

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
  )(implicit caller: Caller): IO[R]

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
  )(implicit caller: Caller): IO[R]

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
  )(implicit caller: Caller): IO[R]

}

object BlazegraphQuery {
  final def apply(
      aclCheck: AclCheck,
      views: CompositeViews,
      client: SparqlClient,
      prefix: String
  ): BlazegraphQuery =
    BlazegraphQuery(
      aclCheck,
      views.fetchIndexingView,
      views.expand,
      client,
      prefix
    )

  private[compositeviews] def apply(
      aclCheck: AclCheck,
      fetchView: FetchView,
      expandId: ExpandId,
      client: SparqlQueryClient,
      prefix: String
  ): BlazegraphQuery =
    new BlazegraphQuery {

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[R] =
        for {
          view       <- fetchView(id, project)
          permissions = view.sparqlProjections.map(_.permission)
          _          <- aclCheck.authorizeForEveryOr(project, permissions)(
                          AuthorizationFailed(s"Defined permissions on sparql projection on '${view.ref}' are missing.")
                        )
          namespace   = commonNamespace(view.uuid, view.indexingRev, prefix)
          result     <- client.query(Set(namespace), query, responseType).adaptError { case e: SparqlClientError =>
                          WrappedBlazegraphClientError(e)
                        }
        } yield result

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[R] =
        for {
          view       <- fetchView(id, project)
          projection <- fetchProjection(view, projectionId)
          _          <-
            aclCheck.authorizeForOr(project, projection.permission)(AuthorizationFailed(project, projection.permission))
          namespace   = projectionNamespace(projection, view.uuid, prefix)
          result     <- client.query(Set(namespace), query, responseType).adaptError { case e: SparqlClientError =>
                          WrappedBlazegraphClientError(e)
                        }
        } yield result

      override def queryProjections[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[R] =
        for {
          view       <- fetchView(id, project)
          namespaces <- allowedProjections(view, project)
          result     <- client.query(namespaces, query, responseType).adaptError { case e: SparqlClientError =>
                          WrappedBlazegraphClientError(e)
                        }
        } yield result

      private def fetchProjection(view: ActiveViewDef, projectionId: IdSegment) =
        expandId(projectionId, view.project).flatMap { id =>
          IO.fromEither(view.sparqlProjection(id))
        }

      private def allowedProjections(view: ActiveViewDef, project: ProjectRef)(implicit
          caller: Caller
      ): IO[Set[String]] =
        aclCheck
          .mapFilterAtAddress[SparqlProjection, String](
            view.sparqlProjections,
            project,
            p => p.permission,
            p => projectionNamespace(p, view.uuid, prefix)
          )
          .flatTap { namespaces =>
            IO.raiseWhen(namespaces.isEmpty)(AuthorizationFailed(s"No views are accessible for view '${view.ref}'."))
          }
    }
}
