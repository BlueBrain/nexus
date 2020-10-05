package ch.epfl.bluebrain.nexus.delta.rdf.graph

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Triple.{predicate, subject, Triple}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jena.writer.DotWriter._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd, JsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.{tryOrConversionErr, IriOrBNode, RdfError, Triple}
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.rdf.model.ResourceFactory.createStatement
import org.apache.jena.rdf.model._
import org.apache.jena.riot.{Lang, RDFWriter}

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

/**
  * A rooted Graph representation backed up by a Jena Model.
  *
  * @param rootNode  the root node of the graph
  * @param model the Jena model
  */
final case class Graph private (rootNode: IriOrBNode, model: Model) { self =>

  lazy val rootResource: Resource = subject(rootNode)

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
    Graph(rootNode, newModel)
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
    val newModel        = ModelFactory.createDefaultModel()
    while (iter.hasNext) {
      val stmt      = iter.nextStatement
      val (s, p, o) = Triple(stmt)
      val ss        = if (s == currentResource) replaceResource else s
      val oo        = if (o == currentResource) replaceResource else o
      newModel.add(ss, p, oo)
    }
    Graph(rootNode, newModel)
  }

  /**
    * Returns all the triples of the current graph
    */
  def triples: Set[Triple] =
    model.listStatements().asScala.map(Triple(_)).toSet

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
    Graph(rootNode, copy(model).add(stmt.asJava))
  }

  /**
    * Attempts to convert the current Graph to the N-Triples format: https://www.w3.org/TR/n-triples/
    */
  def toNTriples: IO[RdfError, NTriples] =
    tryOrConversionErr(RDFWriter.create().lang(Lang.NTRIPLES).source(model).asString(), Lang.NTRIPLES.getName)
      .map(NTriples(_, rootNode))

  /**
    * Attempts to convert the current Graph with the passed ''context''
    * as Json to the DOT format: https://graphviz.org/doc/info/lang.html
    *
    * The context will be inspected to populate its fields and then the conversion will be performed.
    */
  def toDot(
      context: Json = Json.obj()
  )(implicit api: JsonLdApi, resolution: RemoteContextResolution, opts: JsonLdOptions): IO[RdfError, Dot] =
    for {
      resolvedCtx <- JsonLdContext(context)
      ctx          = dotContext(rootResource, resolvedCtx)
      string      <- tryOrConversionErr(RDFWriter.create().lang(DOT).source(model).context(ctx).asString(), DOT.getName)
    } yield Dot(string, rootNode)

  /**
    * Attempts to convert the current Graph with the passed ''context'' as Json
    * to the JSON-LD compacted format:  https://www.w3.org/TR/json-ld11-api/#compaction-algorithms
    *
    * Note: This is done in two steps, first transforming the graph to JSON-LD expanded format and then compacting it.
    */
  def toCompactedJsonLd(context: Json)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, CompactedJsonLd] =
    if (rootNode.isIri) {
      api.fromRdf(model).flatMap(expanded => JsonLd.frame(expanded.asJson, context, rootNode))
    } else {
      // A new model is created where the rootNode is a fake Iri.
      // This is done in order to be able to perform the framing, since framing won't work on blank nodes.
      // After the framing is done, the @id value is removed from the json and the blank node reverted as rootId
      val fakeId   = iri"http://fake.com/${UUID.randomUUID()}"
      val newModel = replace(rootNode, fakeId).model
      for {
        expanded  <- api.fromRdf(newModel)
        framed    <- JsonLd.frame(expanded.asJson, context, fakeId)
        fakeIdJson = fakeId.asJson
      } yield framed.copy(obj = framed.obj.filter { case (_, v) => v != fakeIdJson }, rootId = self.rootNode)
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
    toCompactedJsonLd(Json.obj()).flatMap(_.toExpanded)

  private def copy(model: Model): Model =
    ModelFactory.createDefaultModel().add(model)
}

object Graph {

  /**
    * Creates a [[Graph]] from an expanded JSON-LD.
    *
    * @param expanded the expanded JSON-LD input to transform into a Graph
    */
  final def apply(expanded: ExpandedJsonLd)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      options: JsonLdOptions
  ): IO[RdfError, Graph] =
    if (expanded.rootId.isIri) {
      api.toRdf(expanded.json).map(model => Graph(expanded.rootId, model))
    } else {
      // A fake @id is injected in the Json and then replaced in the model.
      // This is required in order preserve the original blank node since jena will create its own otherwise
      val fakeId = iri"http://fake.com/${UUID.randomUUID()}"
      val json   = Json.arr(expanded.obj.add(keywords.id, fakeId.asJson).asJson)
      api.toRdf(json).map(model => Graph(expanded.rootId, model).replace(fakeId, expanded.rootId))
    }
}
