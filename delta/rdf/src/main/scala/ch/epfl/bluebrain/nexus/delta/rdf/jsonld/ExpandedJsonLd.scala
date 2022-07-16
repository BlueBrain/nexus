package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, JsonLdDecoderError}
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, JsonObject}
import monix.bio.{IO, UIO}

import java.util.UUID

/**
  * Json-LD Expanded Document. This specific implementation is entity centric, having always one root id.
  */
final case class ExpandedJsonLd private (rootId: IriOrBNode, obj: JsonObject) extends JsonLd { self =>

  override lazy val json: Json = Json.arr(obj.asJson)

  /**
    * The cursor for this document
    */
  lazy val cursor: ExpandedJsonLdCursor = ExpandedJsonLdCursor(self)

  override def isEmpty: Boolean = obj.isEmpty

  /**
    * Converts the current document to a [[CompactedJsonLd]]
    */
  def toCompacted(contextValue: ContextValue)(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    CompactedJsonLd(rootId, contextValue, json)

  /**
    * Converts the current document to a [[Graph]]
    */
  def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi
  ): Either[RdfError, Graph] = Graph(self)

  /**
    * Converts the current document to an ''A''
    */
  def to[A](implicit dec: JsonLdDecoder[A]): Either[JsonLdDecoderError, A] =
    dec(self)

  /**
    * Merges the current document with the passed ''that'' on the matching ids.
    *
    * If some keys are present in both documents, the passed one will override the current ones.
    *
    * @param rootId
    *   the new root id of the resulting document
    * @param that
    *   the document to merge with the current one
    */
  def merge(rootId: IriOrBNode, that: ExpandedJsonLd): ExpandedJsonLd =
    ExpandedJsonLd(rootId, obj deepMerge that.obj)

  /**
    * Merges the current document with the passed ''that'' on the matching ids while keeping the ''rootId''.
    *
    * @see
    *   [[merge(rootId, that)]]
    */
  def merge(that: ExpandedJsonLd): ExpandedJsonLd =
    merge(rootId, that)

  /**
    * Replaces the root id value and returns a new [[ExpandedJsonLd]]
    *
    * @param id
    *   the new root id value
    */
  def replaceId(id: IriOrBNode): ExpandedJsonLd =
    id match {
      case _ if id == rootId && obj(keywords.id).contains(rootId.asJson) => self
      case iri: Iri                                                      => ExpandedJsonLd(iri, obj.add(keywords.id, iri.asJson))
      case bNode: IriOrBNode.BNode                                       => ExpandedJsonLd(bNode, obj.remove(keywords.id))
    }

  /**
    * Adds the passed ''key'' and ''iri'' @id to the current document main entry
    */
  def add(key: Iri, iri: Iri): ExpandedJsonLd          =
    add(key.toString, Json.obj(keywords.id -> iri.asJson))

  /**
    * Adds the passed ''key'' and the set of ''iris'' @id to the current document main entry
    */
  def addAll(key: Iri, iris: Set[Iri]): ExpandedJsonLd =
    iris.foldLeft(self) { case (expanded, tpe) =>
      expanded.add(key, tpe)
    }

  /**
    * Adds the passed ''iri'' @type to the current document main entry
    */
  def addType(iri: Iri): ExpandedJsonLd =
    cursor.getTypes match {
      case Right(types) if types.contains(iri) => self
      case _                                   => add(keywords.tpe, iri.asJson)
    }

  /**
    * Adds the passed ''key'' and boolean ''value'' @value to the current document main entry
    */
  def add(key: Iri, value: Boolean): ExpandedJsonLd =
    add(key.toString, Json.obj(keywords.value -> value.asJson))

  /**
    * Adds the passed ''key'' and string ''value'' @value to the current document main entry
    */
  def add(key: Iri, value: String): ExpandedJsonLd  =
    add(key.toString, Json.obj(keywords.value -> value.asJson))

  /**
    * Adds the passed ''key'' and int ''value'' @value to the current document main entry
    */
  def add(key: Iri, value: Int): ExpandedJsonLd     =
    add(key.toString, Json.obj(keywords.value -> value.asJson))

  /**
    * Adds the passed ''key'' and long ''value'' @value to the current document main entry
    */
  def add(key: Iri, value: Long): ExpandedJsonLd    =
    add(key.toString, Json.obj(keywords.value -> value.asJson))

  /**
    * Adds the passed ''key'' and double ''value'' @value to the current document main entry
    */
  def add(key: Iri, value: Double): ExpandedJsonLd  =
    add(key.toString, Json.obj(keywords.value -> value.asJson))

  /**
    * Removes the passed ''key'' from the current document main entry
    */
  def remove(key: Iri): ExpandedJsonLd              =
    copy(obj = obj.remove(key.toString))

  private def add(key: String, value: Json): ExpandedJsonLd =
    obj(key).flatMap(v => v.asArray) match {
      case None      => copy(obj = obj.add(key, Json.arr(value)))
      case Some(arr) => copy(obj = obj.add(key, (arr :+ value).asJson))
    }
}

object ExpandedJsonLd {

  private val bNode   = BNode.random
  private val graphId = iri"http://localhost/${UUID.randomUUID()}"
  private val fakeKey = (nxv + "fake").toString

  /**
    * An empty [[ExpandedJsonLd]] with a random blank node
    */
  val empty: ExpandedJsonLd =
    ExpandedJsonLd(bNode, JsonObject.empty)

  /**
    * Creates a [[ExpandedJsonLd]] document.
    *
    * In case of multiple top level Json Object entries present after expansion has been applied, the first one with an
    * [[Iri]] is selected as the root id.
    *
    * @param input
    *   the input Json document
    */
  final def apply(input: Json)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, ExpandedJsonLd] =
    api.expand(input).flatMap {
      case Seq()       =>
        // try to add a predicate and value in order for the expanded jsonld to at least detect the @id
        for {
          expandedSeq <- api.expand(input deepMerge Json.obj(fakeKey -> "fake".asJson))
          result      <- IO.fromEither(expanded(expandedSeq))
        } yield result.copy(obj = JsonObject.empty)
      case expandedSeq =>
        expandedWithGraphSupport(expandedSeq).map {
          case (result, isGraph) if isGraph => ExpandedJsonLd(bNode, result.obj.remove(keywords.id))
          case (result, _)                  => result
        }

    }

  /**
    * Construct an [[ExpandedJsonLd]] from an existing sequence of [[ExpandedJsonLd]] merging the overriding fields.
    */
  final def apply(seq: Seq[ExpandedJsonLd]): ExpandedJsonLd =
    seq match {
      case head +: tail => tail.foldLeft(head)(_ merge _)
      case _            => ExpandedJsonLd.empty
    }

  /**
    * Constructs a [[ExpandedJsonLd]].
    *
    * @param value
    *   the already expanded document
    */
  final def expanded(value: Json): Either[RdfError, ExpandedJsonLd] =
    for {
      expandedSeq <- value.as[Seq[JsonObject]].leftMap(_ => UnexpectedJsonLd("Expected a Json Array with Json Objects"))
      result      <- expanded(expandedSeq)
    } yield result

  private def expandedWithGraphSupport(expandedSeq: Seq[JsonObject])(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ) =
    for {
      (expandedSeqFinal, isGraph) <-
        if (expandedSeq.size > 1)
          api.expand(Json.obj(keywords.id -> graphId.asJson, keywords.graph -> expandedSeq.asJson)).map(_ -> true)
        else
          UIO.pure((expandedSeq, false))
      result                      <- IO.fromEither(expanded(expandedSeqFinal))

    } yield (result, isGraph)

  private def expanded(expandedSeq: Seq[JsonObject]) =
    for {
      obj    <- unique(expandedSeq)
      rootId <- extractId(obj)
    } yield ExpandedJsonLd(rootId, obj)

  /**
    * Unsafely constructs a [[ExpandedJsonLd]].
    *
    * @param rootId
    *   the root id
    * @param expanded
    *   the already expanded document
    */
  final def unsafe(rootId: IriOrBNode, expanded: JsonObject): ExpandedJsonLd =
    ExpandedJsonLd(rootId, expanded)

  private def unique(seq: Seq[JsonObject]): Either[UnexpectedJsonLd, JsonObject] =
    seq.take(2).toList match {
      case head :: Nil => Right(head)
      case _           => Left(UnexpectedJsonLd("Expected a Json Array with a single Json Object"))
    }

  private def extractId(obj: JsonObject): Either[RdfError.InvalidIri, IriOrBNode] =
    obj(keywords.id).map(_.as[Iri]).getOrElse(Right(BNode.random)).leftMap(_ => InvalidIri)

  object Database {
    implicit final val expandedEncoder: Encoder[ExpandedJsonLd] = Encoder.instance(_.json)
    implicit final val expandedDecoder: Decoder[ExpandedJsonLd] =
      Decoder.decodeJson.emap(ExpandedJsonLd.expanded(_).leftMap(_.getMessage))
  }

}
