package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdError.{IdNotFound, InvalidIri, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, JsonLdContext, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.SeqUtils.headOnlyOption
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.iri.IRI

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
  def rootId: IRI

  /**
    * Adds a ''key'' with its '@id ''iri'' value.
    */
  def add(key: Predicate, iri: IRI): This

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
   * @param context the context to use in order toc ompact the current JsonLd
    */
  def toCompacted[Ctx <: JsonLdContext](context: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions = JsonLdOptions.empty,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IOErrorOr[CompactedJsonLd[Ctx]]

  def toExpanded(implicit
      opts: JsonLdOptions = JsonLdOptions.empty,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IOErrorOr[ExpandedJsonLd]
}
object JsonLd {

  /**
    * Creates an [[ExpandedJsonLd]] unsafely.
    *
   * @param expanded an already expanded Json-LD document. It must be a Json array with a single Json Object inside
    * @param rootId   the top @id value
    * @throws IllegalArgumentException when the provided ''expanded'' json does not match the expected value
    */
  final def expandedUnsafe(expanded: Json, rootId: IRI): ExpandedJsonLd =
    arrayToSingleObject(expanded) match {
      case Right(obj)  => ExpandedJsonLd(obj, rootId)
      case Left(value) => throw new IllegalArgumentException(value.getMessage)
    }

  /**
    * Create an expanded ExpandedJsonLd document using the passed ''input''.
    *
   * If the Json-LD document does not have a root @id, and the ''defaultId'' is present, it creates one.
    *
   * If the Json-LD document does not have a root @id, and the ''defaultId'' is not present, it fails.
    *
   * If the Json-LD document has more than one Json Object inside the array, it fails (@graph with more than an element).
    */
  final def expand(
      input: Json,
      defaultId: => Option[IRI] = None
  )(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions = JsonLdOptions.empty
  ): IOErrorOr[ExpandedJsonLd] =
    for {
      expanded <- api.expand(input)
      obj      <- IO.fromEither(arrayToSingleObject(expanded))
      id       <- (obj(keywords.id), defaultId) match {
                    case (Some(jsonIri), _) => IO.fromEither(jsonIri.as[IRI]).leftMap(_ => InvalidIri(jsonIri.noSpaces))
                    case (_, Some(default)) => IO.now(default)
                    case _                  => IO.raiseError(IdNotFound)
                  }
    } yield ExpandedJsonLd(obj.add(keywords.id, id.asJson), id)

  /**
    * Create compacted JSON-LD document using the passed ''input'' and ''context''.
    *
   * If ContextFields.Include is passed it inspects the Context to include context fields like @base, @vocab, etc.
    *
   * The ''rootId'' is enforced using a framing on it.
    */
  final def compact[Ctx <: JsonLdContext](
      input: Json,
      context: Json,
      rootId: IRI,
      f: ContextFields[Ctx]
  )(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions = JsonLdOptions.empty
  ): IOErrorOr[CompactedJsonLd[Ctx]] = {
    val jsonId = Json.obj(keywords.id -> rootId.asJson)
    val frame  = context.arrayOrObject(jsonId, arr => (arr :+ jsonId).asJson, _.asJson.deepMerge(jsonId))
    for {
      compactedWithCtx <- api.frame(input, frame, f)
      (compacted, ctx)  = compactedWithCtx
      obj              <- IO.fromEither(compacted.asObject.toRight(UnexpectedJsonLd("Expected a Json Object on compacted JSON-LD")))
    } yield CompactedJsonLd(obj, ctx, rootId, f)
  }

  private def arrayToSingleObject(expanded: Json) =
    expanded.asArray
      .flatMap(arr => if (arr.isEmpty) Some(Json.obj()) else headOnlyOption(arr))
      .flatMap(_.asObject)
      .toRight(UnexpectedJsonLd("Expected a Json Array with a single Json Object on expanded JSON-LD"))

}
