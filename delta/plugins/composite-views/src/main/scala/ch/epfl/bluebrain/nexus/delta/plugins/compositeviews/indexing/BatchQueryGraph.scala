package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import fs2.Chunk

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

  private val logger = Logger[BatchQueryGraph]

  def apply(ids: Chunk[Iri]): IO[Option[Graph]] =
    for {
      ntriples    <- client.query(Set(namespace), replaceIds(query, ids), SparqlNTriples)
      graphResult <- NTripleParser(ntriples.value, None)
      _           <- IO.whenA(graphResult.isEmpty)(
                       logger.debug(s"Querying blazegraph did not return any triples, '$ids' will be dropped.")
                     )
    } yield graphResult

  private def replaceIds(query: SparqlConstructQuery, iris: Chunk[Iri]): SparqlConstructQuery = {
    val replacement = iris.foldLeft("") { (acc, iri) => acc + " " + s"<$iri>" }
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), replacement))
  }

}
