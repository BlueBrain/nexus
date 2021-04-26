package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{BlazegraphClient, SparqlClientError, SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.SparqlProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection.{AuthorizationFailed, WrappedBlazegraphClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewRejection, ViewResource, ViewSparqlProjectionResource}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import io.circe.Json
import monix.bio.IO

import scala.xml.NodeSeq

trait BlazegraphQuery {

  /**
    * Queries the blazegraph common namespace of the passed composite view,
    * returning the results in the sparql-results+json representation.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection,
    * returning the results in the sparql-results+json representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryResults(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection,
    * returning the results in the sparql-results+json representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjectionsResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults]

  /**
    * Queries the blazegraph common namespace of the passed composite view,
    * returning the results in the sparql-results+xml representation.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection,
    * returning the results in the sparql-results+xml representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryXml(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection,
    * returning the results in the sparql-results+xml representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjectionsXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

  /**
    * Queries the blazegraph common namespace of the passed composite view,
    * returning the results in the ld+json representation.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection,
    * returning the results in the ld+json representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryJsonLd(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection,
    * returning the results in the ld+json representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjectionsJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, Json]

  /**
    * Queries the blazegraph common namespace of the passed composite view,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql construct query to run
    */
  def queryNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryNTriples(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjectionsNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NTriples]

  /**
    * Queries the blazegraph common namespace of the passed composite view,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on all the views' projections before performing the query.
    *
    * @param id         the id of the composite view either in Iri or aliased form
    * @param project    the project where the view exists
    * @param query      the sparql query to run
    */
  def queryRdfXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

  /**
    * Queries the blazegraph namespace of the passed composite views' projection,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param projectionId the id of the composite views' target projection either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryRdfXml(
      id: IdSegment,
      projectionId: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

  /**
    * Queries all the blazegraph namespaces of the passed composite views' projection,
    * returning the results in the n-triples representation.
    * We check for the caller to have the necessary query permissions on the views' projections before performing the query.
    *
    * @param id           the id of the composite view either in Iri or aliased form
    * @param project      the project where the view exists
    * @param query        the sparql query to run
    */
  def queryProjectionsRdfXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq]

}

object BlazegraphQuery {

  private[compositeviews] type BlazegraphQueryResults  =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, SparqlResults]
  private[compositeviews] type BlazegraphQueryXml      =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, NodeSeq]
  private[compositeviews] type BlazegraphQueryJsonLd   =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, Json]
  private[compositeviews] type BlazegraphQueryNTriples =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, NTriples]
  private[compositeviews] type BlazegraphQueryRdfXml   =
    (Iterable[String], SparqlQuery) => IO[SparqlClientError, NodeSeq]
  private[compositeviews] type FetchView               =
    (IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewResource]
  private[compositeviews] type FetchProjection         =
    (IdSegment, IdSegment, ProjectRef) => IO[CompositeViewRejection, ViewSparqlProjectionResource]

  final def apply(
      acls: Acls,
      views: CompositeViews,
      client: BlazegraphClient
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
    BlazegraphQuery(
      acls,
      views.fetch,
      views.fetchBlazegraphProjection,
      client.queryResults,
      client.queryXml,
      client.queryJsonLd,
      client.queryNTriples,
      client.queryRdfXml
    )

  private[compositeviews] def apply(
      acls: Acls,
      fetchView: FetchView,
      fetchProjection: FetchProjection,
      clientQueryResults: BlazegraphQueryResults,
      clientQueryXml: BlazegraphQueryXml,
      clientQueryJsonLd: BlazegraphQueryJsonLd,
      clientQueryNTriples: BlazegraphQueryNTriples,
      clientQueryRdfXml: BlazegraphQueryRdfXml
  )(implicit config: ExternalIndexingConfig): BlazegraphQuery =
    new BlazegraphQuery {

      override def queryResults(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        accessibleNamespaces(id, project)
          .flatMap(clientQueryResults(_, query).mapError(WrappedBlazegraphClientError))

      override def queryResults(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        accessibleNamespaces(id, projectionId, project)
          .flatMap(clientQueryResults(_, query).mapError(WrappedBlazegraphClientError))

      override def queryProjectionsResults(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, SparqlResults] =
        accessibleProjectionsNamespaces(id, project)
          .flatMap(clientQueryResults(_, query).mapError(WrappedBlazegraphClientError))

      override def queryXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleNamespaces(id, project)
          .flatMap(clientQueryXml(_, query).mapError(WrappedBlazegraphClientError))

      override def queryXml(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleNamespaces(id, projectionId, project)
          .flatMap(clientQueryXml(_, query).mapError(WrappedBlazegraphClientError))

      override def queryProjectionsXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleProjectionsNamespaces(id, project)
          .flatMap(clientQueryXml(_, query).mapError(WrappedBlazegraphClientError))

      override def queryJsonLd(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        accessibleNamespaces(id, project)
          .flatMap(clientQueryJsonLd(_, query).mapError(WrappedBlazegraphClientError))

      override def queryJsonLd(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        accessibleNamespaces(id, projectionId, project)
          .flatMap(clientQueryJsonLd(_, query).mapError(WrappedBlazegraphClientError))

      override def queryProjectionsJsonLd(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, Json] =
        accessibleProjectionsNamespaces(id, project)
          .flatMap(clientQueryJsonLd(_, query).mapError(WrappedBlazegraphClientError))

      override def queryNTriples(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
        accessibleNamespaces(id, project)
          .flatMap(clientQueryNTriples(_, query).mapError(WrappedBlazegraphClientError))

      override def queryNTriples(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
        accessibleNamespaces(id, projectionId, project)
          .flatMap(clientQueryNTriples(_, query).mapError(WrappedBlazegraphClientError))

      override def queryProjectionsNTriples(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NTriples] =
        accessibleProjectionsNamespaces(id, project)
          .flatMap(clientQueryNTriples(_, query).mapError(WrappedBlazegraphClientError))

      override def queryRdfXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleNamespaces(id, project)
          .flatMap(clientQueryRdfXml(_, query).mapError(WrappedBlazegraphClientError))

      override def queryRdfXml(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleNamespaces(id, projectionId, project)
          .flatMap(clientQueryRdfXml(_, query).mapError(WrappedBlazegraphClientError))

      override def queryProjectionsRdfXml(
          id: IdSegment,
          project: ProjectRef,
          query: SparqlQuery
      )(implicit caller: Caller): IO[CompositeViewRejection, NodeSeq] =
        accessibleProjectionsNamespaces(id, project)
          .flatMap(clientQueryRdfXml(_, query).mapError(WrappedBlazegraphClientError))

      private def accessibleNamespaces(
          id: IdSegment,
          project: ProjectRef
      )(implicit caller: Caller): IO[CompositeViewRejection, Set[String]] =
        for {
          viewRes    <- fetchView(id, project)
          permissions = viewRes.value.projections.value.map(_.permission)
          _          <- acls.authorizeForEveryOr(project, permissions)(AuthorizationFailed)
        } yield Set(BlazegraphViews.namespace(viewRes.value.uuid, viewRes.rev, config))

      private def accessibleNamespaces(
          id: IdSegment,
          projectionId: IdSegment,
          project: ProjectRef
      )(implicit caller: Caller): IO[CompositeViewRejection, Set[String]] =
        for {
          viewRes           <- fetchProjection(id, projectionId, project)
          (view, projection) = viewRes.value
          _                 <- acls.authorizeForOr(project, projection.permission)(AuthorizationFailed)
        } yield Set(CompositeViews.namespace(projection, view, viewRes.rev, config.prefix))

      private def accessibleProjectionsNamespaces(
          id: IdSegment,
          project: ProjectRef
      )(implicit caller: Caller): IO[CompositeViewRejection, Set[String]] =
        for {
          viewRes     <- fetchView(id, project)
          view         = viewRes.value
          projections <- allowedProjections(view, project)
        } yield projections.map(p => CompositeViews.namespace(p, view, viewRes.rev, config.prefix)).toSet

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
