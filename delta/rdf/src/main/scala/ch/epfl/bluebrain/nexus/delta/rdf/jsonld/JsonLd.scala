package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

/**
  * Base trait for JSON-LD implementation. This specific implementation is entity centric, having always only one root @id
  */
trait JsonLd extends Product with Serializable {
  type This >: this.type <: JsonLd

  /**
    * The predicate or property will depend on the JSON-LD implementation. Compacted JSON-LD will have short form keys
    * while expanded JSON-LD will have expanded IRIs as keys
    */
  protected type Predicate

  /**
    * The Circe Json Document representation of this JSON-LD
    */
  def json: Json

  /**
    * The top most @id value on the Json-LD Document
    */
  def rootId: IriOrBNode

  /**
    * Adds a ''key'' with its '@id ''iri'' value.
    */
  def add(key: Predicate, iri: Iri): This

  /**
    * Adds the passed ''iri'' value to the reserved key @type.
    */
  def addType(iri: Iri): This

  /**
    * Adds a ''key'' with its ''literal'' string.
    */
  def add(key: Predicate, literal: String): This

  /**
    * Adds a ''key'' with its ''literal'' long.
    */
  def add(key: Predicate, literal: Long): This

  /**
    * Adds a ''key'' with its ''literal'' double.
    */
  def add(key: Predicate, literal: Double): This

  /**
    * Adds a ''key'' with its ''literal'' integer.
    */
  def add(key: Predicate, literal: Int): This

  /**
    * Adds a ''key'' with its ''literal'' boolean.
    */
  def add(key: Predicate, literal: Boolean): This

  /**
    * Converts the current JsonLd into a [[CompactedJsonLd]]
    *
    * @param context the context to use in order to compact the current JsonLd.
    *                E.g.: {"@context": {...}}
    */
  def toCompacted(context: Json)(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd]

  def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd]

  def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Graph]
}
object JsonLd {

  /**
    * Creates an [[CompactedJsonLd]] unsafely.
    *
    * @param compacted    an already compacted Json-LD object
    * @param contextValue the Json-LD context value
    * @param rootId       the top @id value
    */
  final def compactedUnsafe(
      compacted: JsonObject,
      contextValue: ContextValue,
      rootId: IriOrBNode
  ): CompactedJsonLd =
    CompactedJsonLd(compacted, contextValue, rootId)

  /**
    * Creates an [[ExpandedJsonLd]] unsafely.
    *
    * @param value    an already expanded Json-LD document. It must be a Json array with a single Json Object inside
    * @param rootId   the top @id value
    * @throws IllegalArgumentException when the provided ''expanded'' json does not match the expected value
    */
  final def expandedUnsafe(value: Json, rootId: IriOrBNode): ExpandedJsonLd =
    expanded(value, rootId) match {
      case Right(expandedJsonLd) => expandedJsonLd
      case Left(err)             => throw new IllegalArgumentException(err)
    }

  /**
    * Creates an [[ExpandedJsonLd]] with the explicit passed rootId and the already expanded json.
    *
    * @param expanded an already expanded Json-LD document. It must be a Json array with a single Json Object inside
    * @param rootId   the top @id value
    */
  final def expanded(expanded: Json, rootId: IriOrBNode): Either[String, ExpandedJsonLd] =
    expanded.asArray
      .flatMap(_.singleEntryOr(Json.obj()))
      .flatMap(_.asObject)
      .map(obj => ExpandedJsonLd(obj, rootId))
      .toRight("Expected a sequence of Json Objects with a single value")

  /**
    * Create an expanded ExpandedJsonLd document using the passed ''input''.
    *
    * If the Json-LD document has more than one Json Object inside the array, it fails (@graph with more than an element).
    */
  final def expand(input: Json)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, ExpandedJsonLd] =
    for {
      expanded <- api.expand(input)
      obj      <- IO.fromOption(
                    expanded.singleEntryOr(JsonObject.empty),
                    UnexpectedJsonLd("Expected a sequence of Json Objects with a single value")
                  )
      idOpt    <- IO.fromEither(obj.asJson.hcursor.get[Option[Iri]](keywords.id).leftMap(e => InvalidIri(e.message)))
    } yield ExpandedJsonLd(obj, idOpt.getOrElse(BNode.random))

  /**
    * Create compacted JSON-LD document using the passed ''input'' and ''context''.
    *
    * If ContextFields.Include is passed it inspects the Context to include context fields like @base, @vocab, etc.
    *
    * This method does NOT verify the passed ''rootId'' is present in the compacted form. It just verifies the compacted
    * form has the expected format (a Json Object without a top @graph only key)
    */
  final def compact(
      input: Json,
      context: Json,
      rootId: IriOrBNode
  )(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, CompactedJsonLd] =
    for {
      compacted <- api.compact(input, context)
      _         <- topGraphErr(compacted)
    } yield CompactedJsonLd(compacted.remove(keywords.context), context.topContextValueOrEmpty, rootId)

  /**
    * Create compacted JSON-LD document using the passed ''input'' and ''context''.
    *
    * The ''rootId'' is enforced using a framing on it.
    */
  final def frame(
      input: Json,
      context: Json,
      rootId: IriOrBNode
  )(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, CompactedJsonLd] = {
    val jsonId = rootId.asIri.fold(Json.obj())(rootIri => Json.obj(keywords.id -> rootIri.asJson))
    val frame  = context.arrayOrObject(jsonId, arr => (arr :+ jsonId).asJson, _.asJson.deepMerge(jsonId))
    for {
      compacted <- api.frame(input, frame)
      _         <- topGraphErr(compacted)
    } yield CompactedJsonLd(compacted.remove(keywords.context), context.topContextValueOrEmpty, rootId)
  }

  private def topGraphErr(obj: JsonObject): IO[RdfError, Unit] =
    if (obj.nonEmpty && obj.remove(keywords.context).remove(keywords.graph).isEmpty)
      IO.raiseError(UnexpectedJsonLd(s"Expected a Json Object without a single top '${keywords.graph}' field"))
    else
      IO.unit
}
