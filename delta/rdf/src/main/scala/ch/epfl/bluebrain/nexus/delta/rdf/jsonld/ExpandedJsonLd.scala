package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{InvalidIri, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.{JsonLdDecoder, JsonLdDecoderError}
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import monix.bio.IO

import scala.collection.immutable.VectorMap

/**
  * Json-LD Expanded Document. This specific implementation is entity centric, having always one root id.
  */
final case class ExpandedJsonLd private (rootId: IriOrBNode, entries: VectorMap[IriOrBNode, JsonObject])
    extends JsonLd { self =>

  override lazy val json: Json = Json.arr(entries.map { case (_, obj) => obj.asJson }.toSeq: _*)

  /**
    * The cursor for this document
    */
  lazy val cursor: ExpandedJsonLdCursor = ExpandedJsonLdCursor(self)

  override def isEmpty: Boolean = entries.forall { case (_, obj) => obj.isEmpty }

  /**
    * @return true if there is a single entry on the top level of this document, false otherwise
    */
  def singleRoot: Boolean = entries.size == 1

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
    * The resulting order of the entries is the build keeping the entries on the current document and adding the new
    * entries of the passed document afterwards.
    *
    * @param rootId the new root id of the resulting document
    * @param that   the document to merge with the current one
    */
  def merge(rootId: IriOrBNode, that: ExpandedJsonLd): ExpandedJsonLd =
    if (entries.contains(rootId) || that.entries.contains(rootId)) {
      val newEntries = (entries.keys ++ that.entries.keys).foldLeft(entries) { case (acc, id) =>
        val thatObj = that.entries.getOrElse(id, JsonObject.empty)
        acc.updatedWith(id)(objOpt => Some(objOpt.getOrElse(JsonObject.empty).deepMerge(thatObj)))
      }
      ExpandedJsonLd(rootId, newEntries)
    } else self

  /**
    * If the passed ''id'' exists on the current entries of the document, a new [[ExpandedJsonLd]] with the root id
    * pointing to the ''id'' is returned, otherwise None
    */
  def changeRootIfExists(id: IriOrBNode): Option[ExpandedJsonLd] =
    entries.get(id).map(obj => setToFirstEntry(id, id, obj))

  /**
    * Replaces the root id value and returns a new [[ExpandedJsonLd]]
    *
    * @param id the new root id value
    */
  def replaceId(id: IriOrBNode): ExpandedJsonLd =
    id match {
      case _ if id == rootId && mainObj(keywords.id).contains(rootId.asJson) => self
      case iri: Iri                                                          => setToFirstEntry(iri, mainObj.add(keywords.id, iri.asJson))
      case bNode: IriOrBNode.BNode                                           => setToFirstEntry(bNode, mainObj.remove(keywords.id))
    }

  private def setToFirstEntry(id: IriOrBNode, newObj: JsonObject): ExpandedJsonLd =
    setToFirstEntry(id, rootId, newObj)

  private def setToFirstEntry(newId: IriOrBNode, oldId: IriOrBNode, newObj: JsonObject): ExpandedJsonLd =
    if (oldId == newId && newId == rootId)
      ExpandedJsonLd(rootId, entries.updated(rootId, newObj))
    else
      ExpandedJsonLd(newId, VectorMap(newId -> newObj) ++ entries.filterNot { case (id, _) => id == oldId })

  private def mainObj: JsonObject                                                                       = entries(rootId)

}

object ExpandedJsonLd {

  private val bNode = BNode.random

  /**
    * An empty [[ExpandedJsonLd]] with a random blank node
    */
  val empty: ExpandedJsonLd       =
    ExpandedJsonLd(bNode, VectorMap(bNode -> JsonObject.empty))

  /**
    * Creates a [[ExpandedJsonLd]] document.
    *
    * In case of multiple top level Json Object entries present after expansion has been applied, the first one with an
    * [[Iri]] is selected as the root id.
    *
    * @param input the input Json document
    */
  final def apply(input: Json)(implicit
      api: JsonLdApi,
      resolution: RemoteContextResolution,
      opts: JsonLdOptions
  ): IO[RdfError, ExpandedJsonLd] =
    for {
      expandedSeq <- api.expand(input)
      result      <- IO.fromEither(expanded(expandedSeq))
    } yield result

  /**
    * Constructs a [[ExpandedJsonLd]].
    *
    * @param value the already expanded document
    */
  final def expanded(value: Json): Either[RdfError, ExpandedJsonLd] =
    for {
      expandedSeq <- value.as[Seq[JsonObject]].leftMap(_ => UnexpectedJsonLd("Expected a Json Array with Json Objects"))
      result      <- expanded(expandedSeq)
    } yield result

  /**
    * Constructs a [[ExpandedJsonLd]].
    *
    * @param value the already expanded seuqnce of Json Objects
    */
  final def expanded(value: Seq[JsonObject]): Either[RdfError, ExpandedJsonLd] =
    for {
      expandedEntries      <- extractIds(value)
      expandedSortedEntries = selectMainId(expandedEntries)
    } yield expandedSortedEntries.headOption.fold(ExpandedJsonLd.empty) { case (rootId, _) =>
      ExpandedJsonLd(rootId, expandedSortedEntries)
    }

  /**
    * Unsafely constructs a [[ExpandedJsonLd]].
    *
    * @param rootId   the root id
    * @param expanded the already expanded document
    */
  final def unsafe(rootId: IriOrBNode, expanded: JsonObject): ExpandedJsonLd                                   =
    ExpandedJsonLd(rootId, VectorMap(rootId -> expanded))

  private def extractIds(seq: Seq[JsonObject]): Either[RdfError.InvalidIri, VectorMap[IriOrBNode, JsonObject]] =
    seq.toVector.foldM(VectorMap.empty[IriOrBNode, JsonObject]) { (acc, obj) =>
      val extractedId = obj(keywords.id).map(_.as[Iri]).getOrElse(Right(BNode.random))
      extractedId.bimap(_ => InvalidIri, id => acc + (id -> obj))
    }

  /**
    * The main id will be the first one to have an [[Iri]]
    */
  private def selectMainId(entries: VectorMap[IriOrBNode, JsonObject]) =
    entries.find { case (id, _) => id.isIri }.fold(entries) { case (iri, entry) =>
      VectorMap(iri -> entry) ++ entries.filterNot { case (id, _) => id == iri }
    }

}
