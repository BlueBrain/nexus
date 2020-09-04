package ch.epfl.bluebrain.nexus.delta.rdf.graph

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.Triple
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.GraphError.{ConversionError, JsonLdErrorWrapper}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jena.writer.DotWriter._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{ExpandedJsonLd, JsonLdError}
import io.circe.Json
import monix.bio.IO
import org.apache.jena.iri.IRI
import org.apache.jena.rdf.model.ResourceFactory.createStatement
import org.apache.jena.rdf.model._
import org.apache.jena.riot.{Lang, RDFWriter}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * A rooted Graph representation backed up by a Jena Model
  *
 * @param root  the root node of the graph
  * @param model the Jena model
  */
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
    Graph(root, copy(model).add(stmt.asJava))
  }

  /**
    * Attempts to convert the current Graph to the N-Triples format: https://www.w3.org/TR/n-triples/
    * @return
    */
  def toNTriples: Either[GraphError, NTriples] =
    Try {
      RDFWriter.create().lang(Lang.NTRIPLES).source(model).asString()
    }.toEither.map(NTriples(_, root)).leftMap(err => ConversionError(Lang.NTRIPLES.getName, err.getMessage))

  /**
    * Attempts to convert the current Graph to the DOT format: https://graphviz.org/doc/info/lang.html
    * The output will be in expanded form.
    */
  def toDot: Either[GraphError, Dot] =
    toDot(ExtendedJsonLdContext.empty)

  /**
    * Attempts to convert the current Graph with the passed ''context'' as Json to the DOT format: https://graphviz.org/doc/info/lang.html
    * The context will be inspected to populate its fields and then the conversion will be performed.
    */
  def toDot(
      context: Json
  )(implicit api: JsonLdApi, resolution: RemoteContextResolution, opts: JsonLdOptions): IO[GraphError, Dot] =
    api.context(context, ContextFields.Include).leftMap(JsonLdErrorWrapper).flatMap(ctx => IO.fromEither(toDot(ctx)))

  /**
    * Attempts to convert the current Graph with the passed ''context'' as Json to the DOT format: https://graphviz.org/doc/info/lang.html
    * The context will be inspected to populate its fields and then the conversion will be performed.
    */
  def toDot(
      context: RawJsonLdContext
  )(implicit api: JsonLdApi, resolution: RemoteContextResolution, opts: JsonLdOptions): IO[GraphError, Dot] =
    toDot(Json.obj(keywords.context -> context.value))

  /**
    * Attempts to convert the current Graph with the passed ''context'' as Json to the DOT format: https://graphviz.org/doc/info/lang.html
    * If the context is empty the DOT output will have the expanded form
    */
  def toDot(context: ExtendedJsonLdContext): Either[GraphError, Dot]                                        =
    Try {
      RDFWriter.create().lang(DOT).source(model).set(ROOT_ID, rootResource).set(JSONLD_CONTEXT, context).asString()
    }.toEither.map(Dot(_, root)).leftMap(err => ConversionError(DOT.getName, err.getMessage))

  private def copy(model: Model): Model =
    ModelFactory.createDefaultModel().add(model)
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
