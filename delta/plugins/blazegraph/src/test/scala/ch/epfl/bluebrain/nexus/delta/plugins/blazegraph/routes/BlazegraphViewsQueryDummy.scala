package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.routes

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViewsQuery
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.{SparqlQuery, SparqlResults}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.ViewNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{defaultViewId, BlazegraphViewRejection, SparqlLink}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment}
import io.circe.Json
import monix.bio.IO

import scala.xml.NodeSeq

private[routes] class BlazegraphViewsQueryDummy(
    projectRef: ProjectRef,
    sparqlJsonResultsQuery: Map[(String, SparqlQuery), SparqlResults],
    sparqlXmlResultsQuery: Map[(String, SparqlQuery), NodeSeq],
    jsonLdQuery: Map[(String, SparqlQuery), Json],
    nTriplesQuery: Map[(String, SparqlQuery), NTriples],
    xmlQuery: Map[(String, SparqlQuery), NodeSeq],
    links: Map[String, SearchResults[SparqlLink]]
) extends BlazegraphViewsQuery {
  override def incoming(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination.FromPagination
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString), ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def outgoing(
      id: IdSegment,
      project: ProjectRef,
      pagination: Pagination.FromPagination,
      includeExternalLinks: Boolean
  )(implicit caller: Caller, base: BaseUri): IO[BlazegraphViewRejection, SearchResults[SparqlLink]] =
    if (project == projectRef) IO.fromOption(links.get(id.asString), ViewNotFound(defaultViewId, project))
    else IO.raiseError(ViewNotFound(defaultViewId, project))

  override def queryResults(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, SparqlResults] =
    if (project == projectRef)
      IO.fromOption(sparqlJsonResultsQuery.get((id.asString, query)), ViewNotFound(nxv + "id", project))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def queryXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq] =
    if (project == projectRef)
      IO.fromOption(sparqlXmlResultsQuery.get((id.asString, query)), ViewNotFound(nxv + "id", project))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def queryJsonLd(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, Json] =
    if (project == projectRef)
      IO.fromOption(jsonLdQuery.get((id.asString, query)), ViewNotFound(nxv + "id", project))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def queryNTriples(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NTriples] =
    if (project == projectRef)
      IO.fromOption(nTriplesQuery.get((id.asString, query)), ViewNotFound(nxv + "id", project))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))

  override def queryRdfXml(
      id: IdSegment,
      project: ProjectRef,
      query: SparqlQuery
  )(implicit caller: Caller): IO[BlazegraphViewRejection, NodeSeq] =
    if (project == projectRef)
      IO.fromOption(xmlQuery.get((id.asString, query)), ViewNotFound(nxv + "id", project))
    else
      IO.raiseError(ViewNotFound(nxv + "id", project))
}
