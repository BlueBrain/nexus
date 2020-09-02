package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{rdf, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextFields, JsonLdContext, RemoteContextResolution}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, JsonObject}
import monix.bio.IO
import org.apache.jena.iri.IRI

/**
  * Json-LD Expanded Document. This specific implementation is entity centric, having always only one root @id.
  */
final case class ExpandedJsonLd private[jsonld] (obj: JsonObject, rootId: IRI) extends JsonLd { self =>

  type This                = ExpandedJsonLd
  protected type Predicate = IRI

  private lazy val hc = obj.asJson.hcursor

  lazy val json: Json = Json.arr(obj.asJson)

  def add(key: IRI, iri: IRI): This =
    add(key, expand(iri))

  def add(key: IRI, literal: String): This =
    add(key, expand(literal))

  def add(key: IRI, literal: Boolean): This =
    add(key, literal, includeDataType = false)

  def add(key: IRI, literal: Int): This =
    add(key, literal, includeDataType = false)

  def add(key: IRI, literal: Long): This =
    add(key, literal, includeDataType = false)

  def add(key: IRI, literal: Double): This =
    add(key, literal, includeDataType = false)

  def add(key: IRI, literal: Int, includeDataType: Boolean): This =
    add(key, expand(literal, Option.when(includeDataType)(xsd.integer)))

  def add(key: IRI, literal: Boolean, includeDataType: Boolean): This =
    add(key, expand(literal, Option.when(includeDataType)(xsd.boolean)))

  def add(key: IRI, literal: Long, includeDataType: Boolean): This =
    add(key, expand(literal, Option.when(includeDataType)(xsd.long)))

  def add(key: IRI, literal: Double, includeDataType: Boolean): This =
    add(key, expand(literal, Option.when(includeDataType)(xsd.double)))

  /**
    * Fetches the @id values for the passed ''key''.
    */
  def ids(key: IRI): List[IRI] =
    hc.get[List[Id]](key.toString).map(_.map(_.`@id`)).getOrElse(List.empty)

  /**
    * Fetches the literal values for the passed ''key''.
    */
  def literals[A: Decoder](key: IRI): List[A] =
    hc.get[List[Value[A]]](key.toString).map(_.map(_.`@value`)).getOrElse(List.empty)

  /**
    * Fetches the top @type values.
    */
  def types: List[IRI] =
    hc.get[List[IRI]](keywords.tpe).getOrElse(List.empty)

  def toCompacted[Ctx <: JsonLdContext](context: Json, f: ContextFields[Ctx])(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IOErrorOr[CompactedJsonLd[Ctx]] =
    JsonLd.compact(json, context, rootId, f)

  override def toExpanded(implicit
      opts: JsonLdOptions,
      api: JsonLdApi,
      resolution: RemoteContextResolution
  ): IOErrorOr[ExpandedJsonLd] =
    IO.now(self)

  private def add(key: IRI, value: Json): This = {
    val keyString = if (key == rdf.tpe) keywords.tpe else key.toString
    obj(keyString).flatMap(v => v.asArray) match {
      case None      => copy(obj = obj.add(keyString, Json.arr(value)))
      case Some(arr) => copy(obj = obj.add(keyString, (arr :+ value).asJson))
    }
  }

  private def expand[A: Encoder](value: A, dataType: Option[IRI] = None): Json =
    dataType match {
      case Some(dt) => Json.obj(keywords.value -> value.asJson, keywords.tpe -> dt.asJson)
      case None     => Json.obj(keywords.value -> value.asJson)
    }

  private def expand(value: IRI): Json =
    Json.obj(keywords.id -> value.asJson)
}

object ExpandedJsonLd {
  final private[jsonld] case class Value[A](`@value`: A)
  final private[jsonld] case class Id(`@id`: IRI)
  implicit private[jsonld] def decodeJsonLdExpandedValue[A: Decoder]: Decoder[Value[A]] = deriveDecoder[Value[A]]
  implicit private[jsonld] val decodeJsonLdExpandedId: Decoder[Id]                      = deriveDecoder[Id]
}
