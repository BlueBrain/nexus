package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.syntax.kamonSyntax
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.Aux
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewValue.{AggregateBlazegraphViewValue, IndexingBlazegraphViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.*
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.slowqueries.SparqlSlowQueryLogger
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress.Project as ProjectAcl
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.views.View.{AggregateView, IndexingView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.{View, ViewRef, ViewsStore}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

trait BlazegraphViewsQuery {

  /**
    * Queries the blazegraph namespace (or namespaces) managed by the view with the passed ''id''. We check for the
    * caller to have the necessary query permissions on the view before performing the query.
    *
    * @param id
    *   the id of the view either in Iri or aliased form
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
}

object BlazegraphViewsQuery {

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

  final def apply(
      aclCheck: AclCheck,
      fetchContext: FetchContext,
      views: BlazegraphViews,
      client: SparqlQueryClient,
      logSlowQueries: SparqlSlowQueryLogger,
      prefix: String,
      xas: Transactors
  ): BlazegraphViewsQuery = {
    val viewsStore = ViewsStore[BlazegraphViewRejection, BlazegraphViewState](
      BlazegraphViewState.serializer,
      views.fetchState,
      view =>
        IO.raiseWhen(view.deprecated)(ViewIsDeprecated(view.id))
          .as {
            view.value match {
              case _: AggregateBlazegraphViewValue =>
                Left(view.id)
              case i: IndexingBlazegraphViewValue  =>
                Right(
                  IndexingView(
                    ViewRef(view.project, view.id),
                    BlazegraphViews.namespace(view.uuid, view.indexingRev, prefix),
                    i.permission
                  )
                )
            }
          },
      xas
    )
    new BlazegraphViewsQuery {

      private val expandIri: ExpandIri[BlazegraphViewRejection] = new ExpandIri(InvalidResourceId.apply)

      override def query[R <: SparqlQueryResponse](
          id: IdSegment,
          project: ProjectRef,
          sparqlQuery: SparqlQuery,
          responseType: Aux[R]
      )(implicit caller: Caller): IO[R] = {
        for {
          view       <- viewsStore.fetch(id, project)
          p          <- fetchContext.onRead(project)
          iri        <- expandIri(id, p)
          namespaces <- viewToNamespaces(view)
          queryIO     = client.query(namespaces, sparqlQuery, responseType).adaptError { case e: SparqlClientError =>
                          WrappedBlazegraphClientError(e)
                        }
          qr         <- logSlowQueries(ViewRef(project, iri), sparqlQuery, caller.subject, queryIO)
        } yield qr
      }.span("blazegraphUserQuery")

      // Translate a view to the set of underlying namespaces according to the current caller acls
      private def viewToNamespaces(view: View)(implicit caller: Caller) =
        view match {
          case i: IndexingView  =>
            aclCheck
              .authorizeForOr(i.ref.project, i.permission)(
                AuthorizationFailed(i.ref.project, i.permission)
              )
              .as(Set(i.index))
          case a: AggregateView =>
            aclCheck
              .mapFilter[IndexingView, String](
                a.views,
                v => ProjectAcl(v.ref.project) -> v.permission,
                _.index
              )
        }

    }
  }
}
