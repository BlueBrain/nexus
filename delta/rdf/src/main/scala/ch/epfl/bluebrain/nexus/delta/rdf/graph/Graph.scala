package ch.epfl.bluebrain.nexus.delta.rdf.graph

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Quad.Quad
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{ConversionError, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.delta.rdf._
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph.fakeId
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jena.writer.DotWriter._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdJavaApi._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.{IO, UIO}
import org.apache.jena.graph.{Node, Triple => JenaTriple}
import org.apache.jena.query.DatasetFactory
import org.apache.jena.riot.{Lang, RDFParser, RDFWriter}
import org.apache.jena.sparql.core.DatasetGraph
import org.apache.jena.sparql.graph.GraphFactory

import java.util.UUID
import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * A rooted Graph representation backed up by a Jena DatasetGraph.
  *
  * @param rootNode   the root node of the graph
  * @param value      the Jena dataset graph
  */
final case class Graph private (rootNode: IriOrBNode, value: DatasetGraph) { self =>

  private lazy val rootResource: Node = subject(rootNode)

  /**
    * Returns all the triples of the current graph
    */
  lazy val triples: Set[Triple] =
    value.find().asScala.map(Triple(_)).toSet

  /**
    * Returns all the quads of the current graph
    */
  lazy val quads: Set[Quad] =
    value.find().asScala.map(q => (q.getGraph, q.getSubject, q.getPredicate, q.getObject)).toSet

  /**
    * Returns a subgraph retaining all the triples that satisfy the provided predicate.
    */
  def filter(evalTriple: Triple => Boolean): Graph = {
    val iter     = value.find()
    val newGraph = DatasetFactory.create().asDatasetGraph()
    while (iter.hasNext) {
      val quad = iter.next()
      if (evalTriple(Triple(quad))) newGraph.add(quad)
    }
    copy(value = newGraph)
  }

  /**
    * Returns a triple matching the predicate if found.
    */
  def find(evalTriple: Triple => Boolean): Option[Triple] = {
    val iter = value.find()

    @tailrec
    def inner(result: Option[Triple] = None): Option[Triple] =
      if (result.isEmpty && iter.hasNext) {
        val triple = Triple(iter.next())
        inner(Option.when(evalTriple(triple))(triple))
      } else
        result

    inner()
  }

  /**
    * Replace the rootNode with the passed ''newRootNode''.
    */
  def replaceRootNode(newRootNode: IriOrBNode): Graph =
    replace(rootNode, newRootNode).copy(rootNode = newRootNode)

  /**
    * Replace an [[IriOrBNode]] to another [[IriOrBNode]] on the subject or object positions of the graph.
    *
    * @param current the current [[IriOrBNode]]
    * @param replace the replacement when the ''current'' [[IriOrBNode]] is found
    */

  def replace(current: IriOrBNode, replace: IriOrBNode): Graph =
    self.replace(subject(current), subject(replace))

  private def replace(current: Node, replace: Node): Graph = {
    val iter     = value.find()
    val newGraph = DatasetFactory.create().asDatasetGraph()
    while (iter.hasNext) {
      val quad      = iter.next
      val (s, p, o) = Triple(quad)
      val ss        = if (s == current) replace else s
      val oo        = if (o == current) replace else o
      newGraph.add(quad.getGraph, ss, p, oo)
    }
    copy(value = newGraph)
  }

  /**
    * Returns the objects with the predicate ''rdf:type'' and subject ''root''.
    */
  def rootTypes: Set[Iri] =
    filter { case (s, p, _) => p == predicate(rdf.tpe) && s == rootResource }.value
      .find()
      .asScala
      .map(Triple(_))
      .collect {
        case (_, _, r: Node) if r.isURI && r.getURI != null && r.getURI.nonEmpty => iri"${r.getURI}"
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
  def add(p: Node, o: Node): Graph =
    add(Set((rootResource, p, o)))

  /**
    * Adds a set of triples to the existing graph.
    */
  def add(triple: Set[Triple]): Graph = {
    val newGraph = copyGraph(value)
    triple.foreach { case (s, p, o) => newGraph.getDefaultGraph.add(new JenaTriple(s, p, o)) }
    copy(value = newGraph)
  }

  /**
    * Attempts to convert the current Graph to the N-Triples format: https://www.w3.org/TR/n-triples/
    */
  def toNTriples: Either[RdfError, NTriples] =
    tryOrRdfError(
      RDFWriter.create().lang(Lang.NTRIPLES).source(collapseGraphs).asString(),
      Lang.NTRIPLES.getName
    )
      .map(NTriples(_, rootNode))

  /**
    * Attempts to convert the current Graph to the N-Quads format: https://www.w3.org/TR/n-quads/
    */
  def toNQuads: Either[RdfError, NQuads] =
    tryOrRdfError(
      RDFWriter.create().lang(Lang.NQUADS).source(value).asString(),
      Lang.NQUADS.getName
    )
      .map(NQuads(_, rootNode))

  /**
    * Attempts to convert the current Graph with the passed ''context'' value
    * to the DOT format: https://graphviz.org/doc/info/lang.html
    *
    * The context will be inspected to populate its fields and then the conversion will be performed.
    */
  def toDot(
      contextValue: ContextValue = ContextValue.empty
  )(implicit api: JsonLdApi, resolution: RemoteContextResolution, opts: JsonLdOptions): IO[RdfError, Dot] =
    for {
      resolvedCtx <- JsonLdContext(contextValue)
      ctx          = dotContext(rootResource, resolvedCtx)
      string      <-
        ioTryOrRdfError(RDFWriter.create().lang(DOT).source(collapseGraphs).context(ctx).asString(), DOT.getName)
    } yield Dot(string, rootNode)

  /**
    * Attempts to convert the current Graph with the passed ''context'' value
    * to the JSON-LD compacted format:  https://www.w3.org/TR/json-ld11-api/#compaction-algorithms
    *
    * Note: This is done in two steps, first transforming the graph to JSON-LD expanded format and then compacting it.
    */
  def toCompactedJsonLd(contextValue: ContextValue)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, CompactedJsonLd] = {

    def computeCompacted(id: IriOrBNode, input: Json) = {
      if (triples.isEmpty) UIO.delay(CompactedJsonLd.unsafe(id, contextValue, JsonObject.empty))
      else if (value.listGraphNodes().asScala.nonEmpty) CompactedJsonLd(id, contextValue, input)
      else CompactedJsonLd.frame(id, contextValue, input)
    }

    if (rootNode.isBNode)
      for {
        expanded <- IO.fromEither(api.fromRdf(replace(rootNode, fakeId).value))
        framed   <- computeCompacted(fakeId, expanded.asJson)
      } yield framed.replaceId(self.rootNode)
    else
      IO.fromEither(api.fromRdf(value)).flatMap(expanded => computeCompacted(rootNode, expanded.asJson))
  }

  /**
    * Attempts to convert the current Graph to the JSON-LD expanded format: https://www.w3.org/TR/json-ld11-api/#expansion-algorithms
    *
    * Note: This is done in three steps, first transforming the graph to JSON-LD expanded format and then framing it (to have a single root) and then expanding it again.
    */
  def toExpandedJsonLd(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, ExpandedJsonLd] =
    toCompactedJsonLd(ContextValue.empty).flatMap(_.toExpanded)

  /**
    * Merges the current graph with the passed ''that'' while keeping the current ''rootNode''
    */
  def ++(that: Graph): Graph = {
    val newGraph = DatasetFactory.create().asDatasetGraph()
    val quads    = value.find().asScala ++ that.value.find().asScala
    quads.foreach(newGraph.add)
    Graph(rootNode, newGraph)
  }

  override def hashCode(): Int = (rootNode, quads).##

  override def equals(obj: Any): Boolean =
    obj match {
      case that: Graph if rootNode.isBNode && that.rootNode.isBNode => quads == that.quads
      case that: Graph                                              => rootNode == that.rootNode && quads == that.quads
      case _                                                        => false
    }

  override def toString: String =
    s"rootNode = '$rootNode', triples = '$triples'"

  private def copyGraph(graph: DatasetGraph): DatasetGraph = {
    val newGraph = DatasetFactory.create().asDatasetGraph()
    graph.find().asScala.foreach(newGraph.add)
    newGraph
  }

  private def collapseGraphs = {
    val newGraph = GraphFactory.createDefaultGraph()
    value.find().asScala.foreach(quad => newGraph.add(quad.asTriple()))
    newGraph
  }

}

object Graph {

  // This fake id is used in cases where the ''rootNode'' is a Blank Node.
  // Since a Blank Node is ephemeral, its value can change on any conversion Json-LD <-> Graph.
  // We replace the Blank Node for the fakeId, do the conversion and then replace the fakeId with the Blank Node again.
  private[graph] val fakeId = iri"http://localhost/${UUID.randomUUID()}"

  /**
    * An empty graph with a auto generated [[BNode]] as a root node
    */
  final val empty: Graph = empty(BNode.random)

  /**
    * An empty graph with the passed ''rootNode''.
    */
  final def empty(rootNode: IriOrBNode): Graph = Graph(rootNode, DatasetFactory.create().asDatasetGraph())

  /**
    * Creates a [[Graph]] from n-quads representation.
    */
  final def apply(nQuads: NQuads): Either[RdfError, Graph] = {
    val g = DatasetFactory.create().asDatasetGraph()
    Try(RDFParser.create().fromString(nQuads.value).lang(Lang.NQUADS).parse(g)).toEither
      .leftMap(err => ConversionError(err.getMessage, "n-quads"))
      .map(_ => Graph(nQuads.rootNode, g))
  }

  /**
    * Creates a [[Graph]] from an expanded JSON-LD.
    *
    * @param expanded the expanded JSON-LD input to transform into a Graph
    */
  final def apply(
      expanded: ExpandedJsonLd
  )(implicit api: JsonLdApi, options: JsonLdOptions): Either[RdfError, Graph] =
    (expanded.obj(keywords.graph), expanded.rootId) match {
      case (Some(_), _: BNode) =>
        Left(UnexpectedJsonLd("Expected named graph, but root @id not found"))
      case (Some(_), iri: Iri) =>
        api.toRdf(expanded.json).map(g => Graph(iri, g))
      case (None, _: BNode)    =>
        val json = expanded.replaceId(fakeId).json
        api.toRdf(json).map(g => Graph(expanded.rootId, g).replace(fakeId, expanded.rootId))
      case (None, iri: Iri)    =>
        api.toRdf(expanded.json).map(g => Graph(iri, g))
    }

  /**
    * Unsafely builds a graph from an already passed [[DatasetGraph]] and an auto generated [[BNode]] as a root node
    */
  final def unsafe(graph: DatasetGraph): Graph =
    unsafe(BNode.random, graph)

  /**
    * Unsafely builds a graph from an already passed [[DatasetGraph]] and the passed [[IriOrBNode]]
    */
  final def unsafe(rootNode: IriOrBNode, graph: DatasetGraph): Graph =
    Graph(rootNode, graph)

}
