package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import fs2.Chunk
import monix.bio.Task

import java.util.regex.Pattern.quote

/**
  * Provides a way to query for the multiple incoming resources (from a chunk). This assumes that the query contains the
  * template: `VALUE ?id { {resource_id} }`. The result is a single Graph for all given resources.
  * @param client
  *   the blazegraph client used to query
  * @param namespace
  *   the namespace to query
  * @param query
  *   the sparql query to perform
  */
final class BatchQueryGraph(client: BlazegraphClient, namespace: String, query: SparqlConstructQuery) {

  private val logger: Logger = Logger[BatchQueryGraph]

  private def newGraph(ntriples: NTriples): Task[Option[Graph]] =
    if (ntriples.isEmpty) Task.none
    else Task.fromEither(Graph(ntriples)).map(Some(_))

  def apply(ids: Chunk[Iri]): Task[Option[Graph]] =
    for {
      ntriples    <- client.query(Set(namespace), replaceIds(query, ids), SparqlNTriples)
      graphResult <- newGraph(ntriples.value)
      _           <- Task.when(graphResult.isEmpty)(
                       logger.debug(s"Querying blazegraph did not return any triples, '$ids' will be dropped.")
                     )
    } yield graphResult

  private def replaceIds(query: SparqlConstructQuery, iris: Chunk[Iri]): SparqlConstructQuery = {
    val replacement = iris.foldLeft("") { (acc, iri) => acc + " " + s"<$iri>" }
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), replacement))
  }

}

/**
  * Provides a way to query for the incoming resource and replaces the graph with the result of query
  * @param client
  *   the blazegraph client used to query
  * @param namespace
  *   the namespace to query
  * @param query
  *   the query to perform on each resource
  */
final class QueryGraph(client: BlazegraphClient, namespace: String, query: SparqlConstructQuery) {

  private val logger: Logger = Logger[QueryGraph]

  private def newGraph(ntriples: NTriples, id: Iri): Task[Option[Graph]] =
    if (ntriples.isEmpty) {
      // If nothing is returned by the query, we skip
      Task.none
    } else
      Task.fromEither(Graph(ntriples.copy(rootNode = id))).map { g =>
        Some(g.replaceRootNode(id))
      }

  def apply(graphResource: GraphResource): Task[Option[GraphResource]] =
    for {
      ntriples    <- client.query(Set(namespace), replaceId(query, graphResource.id), SparqlNTriples)
      graphResult <- newGraph(ntriples.value, graphResource.id)
      _           <- Task.when(graphResult.isEmpty)(
                       logger.debug(s"Querying blazegraph did not return any triples, '$graphResource' will be dropped.")
                     )
    } yield graphResult.map(g => graphResource.copy(graph = g))

  private def replaceId(query: SparqlConstructQuery, iri: Iri): SparqlConstructQuery =
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), s"<$iri>"))
}
