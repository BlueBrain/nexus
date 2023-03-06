package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.QueryGraph.logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.idTemplating
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Graph, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Pipe
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, PipeRef}
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import shapeless.Typeable

import java.util.regex.Pattern.quote

/**
  * Pipe that performs the provided query for the incoming resource and replaces the graph with the result of query
  * @param client
  *   the blazegraph client
  * @param namespace
  *   the namespace to query
  * @param query
  *   the query to perform on each resource
  */
final class QueryGraph(client: BlazegraphClient, namespace: String, query: SparqlConstructQuery) extends Pipe {

  override type In  = GraphResource
  override type Out = GraphResource
  override def ref: PipeRef                     = QueryGraph.ref
  override def inType: Typeable[GraphResource]  = Typeable[GraphResource]
  override def outType: Typeable[GraphResource] = Typeable[GraphResource]

  private def newGraph(ntriples: NTriples, id: Iri): Task[Option[Graph]] =
    if (ntriples.isEmpty) {
      // If nothing is returned by the query, we skip
      Task.none
    } else
      Task.fromEither(Graph(ntriples.copy(rootNode = id))).map { g =>
        Some(g.replaceRootNode(id))
      }

  override def apply(element: SuccessElem[GraphResource]): Task[Elem[GraphResource]] =
    for {
      ntriples    <- client.query(Set(namespace), replaceId(query, element.id), SparqlNTriples)
      graphResult <- newGraph(ntriples.value, element.id)
      _           <- Task.when(graphResult.isEmpty)(
                       Task.delay(logger.warn(s"Querying blazegraph did not return any triples, '$element' will be dropped."))
                     )
    } yield graphResult.map(g => element.map(_.copy(graph = g))).getOrElse(element.dropped)

  private def replaceId(query: SparqlConstructQuery, iri: Iri): SparqlConstructQuery =
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), s"<$iri>"))
}

object QueryGraph {

  private val logger: Logger = Logger[QueryGraph]

  val ref: PipeRef = PipeRef.unsafe("query-graph")

}
