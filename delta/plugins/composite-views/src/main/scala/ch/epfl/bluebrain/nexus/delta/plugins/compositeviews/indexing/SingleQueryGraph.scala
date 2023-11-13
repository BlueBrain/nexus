package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource

import java.util.regex.Pattern.quote

/**
  * Provides a way to query for the incoming resource and replaces the graph with the result of query
  *
  * @param client
  *   the blazegraph client used to query
  * @param namespace
  *   the namespace to query
  * @param query
  *   the query to perform on each resource
  */
final class SingleQueryGraph(client: BlazegraphClient, namespace: String, query: SparqlConstructQuery) {

  private val logger = Logger[SingleQueryGraph]

  private def newGraph(ntriples: NTriples, id: Iri): IO[Option[Graph]] =
    if (ntriples.isEmpty) {
      // If nothing is returned by the query, we skip
      IO.none
    } else
      IO.fromEither(Graph(ntriples.copy(rootNode = id))).map { g =>
        Some(g.replaceRootNode(id))
      }

  def apply(graphResource: GraphResource): IO[Option[GraphResource]] =
    for {
      ntriples    <- client.query(Set(namespace), replaceId(query, graphResource.id), SparqlNTriples)
      graphResult <- newGraph(ntriples.value, graphResource.id)
      _           <- IO.whenA(graphResult.isEmpty)(
                       logger.debug(s"Querying blazegraph did not return any triples, '$graphResource' will be dropped.")
                     )
    } yield graphResult.map(g => graphResource.copy(graph = g))

  private def replaceId(query: SparqlConstructQuery, iri: Iri): SparqlConstructQuery =
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), s"<$iri>"))
}
