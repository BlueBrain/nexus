package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, JsonLdError}
import io.circe.Json
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.apache.jena.rdf.model.ResourceFactory.createStatement
import org.apache.jena.rdf.model._

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

final case class Graph private (root: IRI, model: Model) { self =>

  lazy val rootResource: Resource = subject(root)

  /**
    * Returns a subgraph retaining all the triples that satisfy the provided predicate.
    */
  def filter(evalTriple: Triple => Boolean): Graph = {
    val iter     = model.listStatements()
    val newModel = ModelFactory.createDefaultModel()
    while (iter.hasNext) {
      val stmt = iter.nextStatement
      if (evalTriple(Triple(stmt))) newModel.add(stmt)
    }
    Graph(root, newModel)
  }

  /**
    * Returns a triple matching the predicate if found.
    */
  def find(evalTriple: Triple => Boolean): Option[Triple] = {
    val iter = model.listStatements()

    @tailrec
    def inner(result: Option[Triple] = None): Option[Triple] =
      if (result.isEmpty && iter.hasNext) {
        val triple = Triple(iter.nextStatement)
        inner(Option.when(evalTriple(triple))(triple))
      } else
        result

    inner()
  }

  /**
    * Returns all the triples of the current graph
    */
  def triples: Set[Triple] =
    model.listStatements().asScala.map(Triple(_)).toSet

  /**
    * Returns the objects with the predicate ''rdf:type'' and subject ''root''.
    */
  def rootTypes: Set[IRI] =
    filter { case (s, p, _) => p == predicate(rdf.tpe) && s == rootResource }.model
      .listObjects()
      .asScala
      .collect {
        case r: Resource if r.isURIResource && r.getURI != null && !r.getURI.isEmpty => iri"${r.getURI}"
      }
      .toSet

  /**
    * Adds the passed ''triple'' to the existing graph.
    */
  def add(triple: Triple): Graph =
    add(Set(triple))

  /**
    * Adds a triple using the current ''root'' subject and the passed predicate ''p'' and object ''o''.
    */
  def add(p: Property, o: RDFNode): Graph =
    add(Set((rootResource, p, o)))

  /**
    * Adds a set of triples to the existing graph.
    */
  def add(triple: Set[Triple]): Graph = {
    val stmt = triple.foldLeft(Vector.empty[Statement]) { case (acc, (s, p, o)) => acc :+ createStatement(s, p, o) }
    Graph(root, model.add(stmt.asJava))
  }
}

object Graph {

  /**
    * Creates a [[Graph]] from a JSON-LD.
    *
   * @param iri   the root IRI for the graph
    * @param input the JSON-LD input to transform into a Graph
    */
  final def apply(iri: IRI, input: Json)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      options: JsonLdOptions = JsonLdOptions.empty
  ): IO[JsonLdError, Graph] =
    api.toRdf(input).map(m => Graph(iri, m))

  /**
    * Create a [[Graph]] from an expanded JSON-LD.
    */
  final def from(expanded: ExpandedJsonLd)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      options: JsonLdOptions = JsonLdOptions.empty
  ): IO[JsonLdError, Graph] =
    apply(expanded.rootId, expanded.json)

}
