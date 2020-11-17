package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.{IriOrBNode, RdfError}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, JsonObject}
import monix.bio.IO

/**
  * Json-LD Expanded Document. This specific implementation is entity centric, having always only one root @id.
  */
final case class ExpandedJsonLd private[jsonld] (obj: JsonObject, rootId: IriOrBNode) extends JsonLd { self =>

  override type This = ExpandedJsonLd

  protected type Predicate = Iri

  private lazy val hc = obj.asJson.hcursor

  lazy val json: Json = Json.arr(obj.asJson)

  private val rootIdJson = rootId.asJson

  def add(key: Predicate, iri: Iri): This =
    add(key.toString, expand(iri))

  def addType(iri: Iri): This =
    add(keywords.tpe, iri.asJson)

  def add(key: Predicate, literal: String): This =
    add(key.toString, expand(literal))

  def add(key: Predicate, literal: Boolean): This =
    add(key, literal, includeDataType = false)

  def add(key: Predicate, literal: Int): This =
    add(key, literal, includeDataType = false)

  def add(key: Predicate, literal: Long): This =
    add(key, literal, includeDataType = false)

  def add(key: Predicate, literal: Double): This =
    add(key, literal, includeDataType = false)

  def add(key: Iri, literal: Int, includeDataType: Boolean): This =
    add(key.toString, expand(literal, Option.when(includeDataType)(xsd.integer)))

  def add(key: Iri, literal: Boolean, includeDataType: Boolean): This =
    add(key.toString, expand(literal, Option.when(includeDataType)(xsd.boolean)))

  def add(key: Iri, literal: Long, includeDataType: Boolean): This =
    add(key.toString, expand(literal, Option.when(includeDataType)(xsd.long)))

  def add(key: Iri, literal: Double, includeDataType: Boolean): This =
    add(key.toString, expand(literal, Option.when(includeDataType)(xsd.double)))

  /**
    * Fetches the @id values for the passed ''key''.
    */
  def ids(key: Iri): List[Iri] =
    hc.get[List[Id]](key.toString).map(_.map(_.`@id`)).getOrElse(List.empty)

  /**
    * Fetches the literal values for the passed ''key''.
    */
  def literals[A: Decoder](key: Iri): List[A] =
    hc.get[List[Value[A]]](key.toString).map(_.map(_.`@value`)).getOrElse(List.empty)

  /**
    * Fetches the top @type values.
    */
  def types: List[Iri] =
    hc.get[List[Iri]](keywords.tpe).getOrElse(List.empty)

  /**
    * Replaces the root @id value and returns a new [[ExpandedJsonLd]]
    *
    * @param iriOrBNode the new root @id value
    */
  def replaceId(iriOrBNode: IriOrBNode): ExpandedJsonLd =
    iriOrBNode match {
      case _ if iriOrBNode == rootId && obj(keywords.id) == Some(rootIdJson) => self
      case iri: Iri                                                          => new ExpandedJsonLd(obj.add(keywords.id, iri.asJson), iri)
      case bNode: IriOrBNode.BNode                                           => new ExpandedJsonLd(obj.remove(keywords.id), bNode)
    }

  def toCompacted(contextValue: ContextValue)(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, CompactedJsonLd] =
    JsonLd.compact(json, contextValue, rootId)

  override def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, ExpandedJsonLd] =
    IO.pure(self)

  override def toGraph(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IO[RdfError, Graph] = Graph(this)

  override def isEmpty: Boolean = obj.isEmpty

  private def add(key: String, value: Json): This =
    obj(key).flatMap(v => v.asArray) match {
      case None      => copy(obj = obj.add(key, Json.arr(value)))
      case Some(arr) => copy(obj = obj.add(key, (arr :+ value).asJson))
    }

  private def expand[A: Encoder](value: A, dataType: Option[Iri] = None): Json =
    dataType match {
      case Some(dt) => Json.obj(keywords.value -> value.asJson, keywords.tpe -> dt.asJson)
      case None     => Json.obj(keywords.value -> value.asJson)
    }

  private def expand(value: Iri): Json =
    Json.obj(keywords.id -> value.asJson)
}

object ExpandedJsonLd {
  final private[jsonld] case class Value[A](`@value`: A)
  final private[jsonld] case class Id(`@id`: Iri)
  implicit private[jsonld] def decodeJsonLdExpandedValue[A: Decoder]: Decoder[Value[A]] = deriveDecoder[Value[A]]
  implicit private[jsonld] val decodeJsonLdExpandedId: Decoder[Id]                      = deriveDecoder[Id]
}
