package ch.epfl.bluebrain.nexus.delta.rdf.graph

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph.{emptyModel, fakeId}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jena.writer.DotWriter._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf._
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.graph.Factory.createDefaultGraph
import org.apache.jena.rdf.model.ResourceFactory.createStatement
import org.apache.jena.rdf.model._
import org.apache.jena.riot.{Lang, RDFWriter}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdJavaApi._

/**
  * A rooted Graph representation backed up by a Jena Model.
  *
  * @param rootNode       the root node of the graph
  * @param model          the Jena model
  * @param frameOnCompact flag to decide whether or not to frame when compacting the graph.
  */
final case class Graph private (rootNode: IriOrBNode, model: Model, private val frameOnCompact: Boolean) { self =>

  private lazy val rootResource: Resource = subject(rootNode)

  /**
    * Returns all the triples of the current graph
    */
  lazy val triples: Set[Triple] =
    model.listStatements().asScala.map(Triple(_)).toSet

  /**
    * Returns a subgraph retaining all the triples that satisfy the provided predicate.
    */
  def filter(evalTriple: Triple => Boolean): Graph = {
    val iter     = model.listStatements()
    val newModel = emptyModel()
    while (iter.hasNext) {
      val stmt = iter.nextStatement
      if (evalTriple(Triple(stmt))) newModel.add(stmt)
    }
    copy(model = newModel)
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
    * Replace an [[IriOrBNode]] to another [[IriOrBNode]] on the subject or object positions of the graph.
    *
    * @param current the current [[IriOrBNode]]
    * @param replace the replacement when the ''current'' [[IriOrBNode]] is found
    */
  def replace(current: IriOrBNode, replace: IriOrBNode): Graph = {
    val currentResource = subject(current)
    val replaceResource = subject(replace)
    val iter            = model.listStatements()
    val newModel        = emptyModel()
    while (iter.hasNext) {
      val stmt      = iter.nextStatement
      val (s, p, o) = Triple(stmt)
      val ss        = if (s == currentResource) replaceResource else s
      val oo        = if (o == currentResource) replaceResource else o
      newModel.add(ss, p, oo)
    }
    copy(model = newModel)
  }

  /**
    * Returns the objects with the predicate ''rdf:type'' and subject ''root''.
    */
  def rootTypes: Set[Iri] =
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
    copy(model = copyModel(model).add(stmt.asJava))
  }

  /**
    * Attempts to convert the current Graph to the N-Triples format: https://www.w3.org/TR/n-triples/
    */
  def toNTriples: Either[RdfError, NTriples] =
    tryOrRdfError(RDFWriter.create().lang(Lang.NTRIPLES).source(model).asString(), Lang.NTRIPLES.getName)
      .map(NTriples(_, rootNode))

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
      string      <- ioTryOrRdfError(RDFWriter.create().lang(DOT).source(model).context(ctx).asString(), DOT.getName)
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

    def computeCompacted(id: IriOrBNode, input: Json) =
      if (frameOnCompact) CompactedJsonLd.frame(id, contextValue, input)
      else CompactedJsonLd(id, contextValue, input)

    if (rootNode.isBNode && frameOnCompact)
      for {
        expanded <- IO.fromEither(api.fromRdf(replace(rootNode, fakeId).model))
        framed   <- computeCompacted(fakeId, expanded.asJson)
      } yield framed.replaceId(self.rootNode)
    else
      IO.fromEither(api.fromRdf(model)).flatMap(expanded => computeCompacted(rootNode, expanded.asJson))
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
  def ++(that: Graph): Graph =
    Graph(rootNode, emptyModel().add(model).add(that.model), frameOnCompact)

  override def hashCode(): Int = (rootNode, triples).##

  override def equals(obj: Any): Boolean =
    obj match {
      case that: Graph if rootNode.isBNode && that.rootNode.isBNode => triples == that.triples
      case that: Graph                                              => rootNode == that.rootNode && triples == that.triples
      case _                                                        => false
    }

  override def toString: String =
    s"rootNode = '$rootNode', triples = '$triples'"

  /**
    * Replaces the root node
    *
    * @param node the new root node resource
    */
  private[rdf] def replaceRootNode(node: Resource): Graph =
    if (node.isAnon) copy(rootNode = BNode.unsafe(node.getId.getLabelString))
    else if (node.isURIResource) copy(rootNode = Iri.unsafe(node.getURI))
    else self

  private def copyModel(model: Model): Model =
    emptyModel().add(model)
}

object Graph {

  // This fake id is used in cases where the ''rootNode'' is a Blank Node.
  // Since a Blank Node is ephemeral, its value can change on any conversion Json-LD <-> Graph.
  // We replace the Blank Node for the fakeId, do the conversion and then replace the fakeId with the Blank Node again.
  private[graph] val fakeId = iri"http://localhost/${UUID.randomUUID()}"

  /**
    * An empty graph with a auto generated [[BNode]] as a root node
    */
  final val empty: Graph = Graph(BNode.random, emptyModel(), frameOnCompact = true)

  /**
    * Creates a [[Graph]] from an expanded JSON-LD.
    *
    * @param expanded the expanded JSON-LD input to transform into a Graph
    */
  final def apply(expanded: ExpandedJsonLd)(implicit api: JsonLdApi, options: JsonLdOptions): Either[RdfError, Graph] =
    if (expanded.rootId.isIri) {
      api.toRdf(expanded.json).map(model => Graph(expanded.rootId, model, expanded.singleRoot))
    } else {
      val json = expanded.replaceId(fakeId).json
      api.toRdf(json).map(model => Graph(expanded.rootId, model, expanded.singleRoot).replace(fakeId, expanded.rootId))
    }

  /**
    * Unsafely builds a graph from an already passed [[Model]] and an auto generated [[BNode]] as a root node
    */
  final def unsafe(model: Model): Graph =
    unsafe(BNode.random, model)

  /**
    * Unsafely builds a graph from an already passed [[Model]] and the passed [[IriOrBNode]]
    */
  final def unsafe(rootNode: IriOrBNode, model: Model): Graph =
    Graph(rootNode, model, frameOnCompact = true)

  private[rdf] def emptyModel(): Model = ModelFactory.createModelForGraph(createDefaultGraph())

}
