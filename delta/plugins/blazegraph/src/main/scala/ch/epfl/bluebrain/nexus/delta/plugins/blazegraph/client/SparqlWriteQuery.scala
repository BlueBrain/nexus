package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples

/**
  * Enumeration type for all supported sparql write queries
  */
sealed trait SparqlWriteQuery extends Product with Serializable {

  /**
    * @return the underlying query value
    */
  def value: String

  /**
    * @return the targeted named graph
    */
  def graph: Uri
}

object SparqlWriteQuery {

  /**
    * A Drop query
    */
  final case class SparqlDropQuery private (value: String, graph: Uri) extends SparqlWriteQuery

  /**
    * A Patch query
    */
  final case class SparqlPatchQuery private (value: String, graph: Uri) extends SparqlWriteQuery

  /**
    * A Replace query (drop + insert)
    */
  final case class SparqlReplaceQuery private (value: String, graph: Uri) extends SparqlWriteQuery

  /**
    * A custom write query
    */
  final case class SparqlCustomQuery(value: String, graph: Uri) extends SparqlWriteQuery

  /**
    * Builds a query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param graph the graph to drop
    */
  def drop(graph: Uri): SparqlDropQuery =
    SparqlDropQuery(s"DROP GRAPH <$graph>;", graph)

  /**
    * Builds a query that patches the graph by selecting a collection of triples to remove or retain and inserting the triples in the data
    * argument.
    *
    * @param graph    the target graph
    * @param data     the additional graph content in NTriples representation
    * @param strategy the patch strategy
    */
  def patch(graph: Uri, data: NTriples, strategy: PatchStrategy): SparqlPatchQuery = {
    val filterExpr = strategy match {
      case RemovePredicates(predicates)    => predicates.map(p => s"?p = <$p>").mkString(" || ")
      case RemoveButPredicates(predicates) => predicates.map(p => s"?p != <$p>").mkString(" && ")
    }
    SparqlPatchQuery(
      s"""DELETE {
         |  GRAPH <$graph> {
         |    ?s ?p ?o .
         |  }
         |}
         |WHERE {
         |  GRAPH <$graph> {
         |    ?s ?p ?o .
         |    FILTER ( $filterExpr )
         |  }
         |};
         |
         |INSERT DATA {
         |  GRAPH <$graph> {
         |    $data
         |  }
         |};""".stripMargin,
      graph
    )

  }

  /**
    * Builds a query that removes all triples from the graph identified by the argument URI and stores the triples in the data argument in
    * the same graph.
    *
    * @param graph the target graph
    * @param data  the new graph in NTriples representation
    */
  def replace(graph: Uri, data: NTriples): SparqlReplaceQuery =
    SparqlReplaceQuery(
      s"""DROP GRAPH <$graph>;
           |
           |INSERT DATA {
           |  GRAPH <$graph> {
           |    $data
           |  }
           |};""".stripMargin,
      graph
    )
}
