package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, JsonObject}

/**
  * The Json value of the @context key
  */
sealed trait ContextValue {

  /**
    * @return the json representation of the context value
    */
  def value: Json

  override def toString: String = value.noSpaces

  /**
    * @return true if the current context value is empty, false otherwise
    */
  def isEmpty: Boolean

  /**
    * The context object. E.g.: {"@context": {...}}
    */
  def contextObj: JsonObject =
    if (isEmpty) JsonObject.empty
    else JsonObject(keywords.context -> value)

  /**
    * Combines the current [[ContextValue]] context with a passed [[ContextValue]] context.
    * If a keys are is repeated in both contexts, the one in ''that'' will override the current one.
    *
    * @param that another context to be merged with the current
    * @return the merged context
    */
  def merge(that: ContextValue): ContextValue

  /**
    * Adds a key value to the current context
    */
  def add(key: String, value: Json): ContextValue =
    merge(ContextObject(JsonObject(key -> value)))
}

object ContextValue {

  /**
    * An empty context value
    */
  final case object ContextEmpty extends ContextValue {
    override val value: Json                             = Json.obj()
    override val isEmpty: Boolean                        = true
    override def merge(that: ContextValue): ContextValue = that
  }

  /**
    * An array of context value entries (iris or json objects)
    */
  final case class ContextArray(ctx: Vector[ContextValueEntry]) extends ContextValue { self =>
    override def value: Json                             = ctx.map(_.value).asJson
    override val isEmpty: Boolean                        = false
    override def merge(that: ContextValue): ContextValue =
      that match {
        case ContextEmpty                                        => self
        case thatCtx: ContextValueEntry if ctx.contains(thatCtx) => self
        case thatCtx: ContextValueEntry                          => ContextArray(ctx :+ thatCtx)
        case ContextArray(thatCtx)                               => ContextArray((ctx ++ thatCtx).distinct)
      }
  }

  sealed trait ContextValueEntry extends ContextValue

  /**
    * A remote Iri context value
    */
  final case class ContextRemoteIri(iri: Iri) extends ContextValueEntry { self =>
    override def value: Json                             = iri.asJson
    override val isEmpty: Boolean                        = false
    override def merge(that: ContextValue): ContextValue =
      that match {
        case ContextEmpty                                    => self
        case ContextRemoteIri(`iri`)                         => self
        case ctx: ContextValueEntry                          => ContextArray(Vector(self, ctx))
        case ContextArray(thatCtx) if thatCtx.contains(self) => that
        case ContextArray(thatCtx)                           => ContextArray(self +: thatCtx)
      }
  }

  /**
    * A json object context value
    */
  final case class ContextObject(obj: JsonObject) extends ContextValueEntry { self =>
    override def value: Json                             = obj.asJson
    override val isEmpty: Boolean                        = false
    override def merge(that: ContextValue): ContextValue =
      that match {
        case ContextEmpty                                    => self
        case ContextObject(`obj`)                            => self
        case ContextObject(thatObj)                          => ContextObject(obj deepMerge thatObj)
        case ctx: ContextRemoteIri                           => ContextArray(Vector(self, ctx))
        case ContextArray(thatCtx) if thatCtx.contains(self) => that
        case ContextArray(thatCtx)                           => ContextArray(self +: thatCtx)
      }
  }

  /**
    * An empty [[ContextValue]]
    */
  val empty: ContextValue = ContextEmpty

  /**
    * Construct a [[ContextValue]] from remote context [[Iri]]s.
    */
  final def apply(iri: Iri*): ContextValue =
    iri.toList match {
      case Nil         => empty
      case head :: Nil => ContextRemoteIri(head)
      case rest        => ContextArray(rest.map(ContextRemoteIri).toVector)
    }

  /**
    * Constructs a [[ContextValue]] from a json. The value of the json must be the value of the @context key
    */
  final def apply(json: Json): ContextValue =
    // format: off
    (json.asObject.filter(_.nonEmpty).map(ContextObject) orElse
      json.asArray.filter(_.nonEmpty).map(arr => ContextArray(arr.map(apply).collect { case c: ContextValueEntry => c })) orElse
      json.as[Iri].toOption.filter(_.isAbsolute).map(ContextRemoteIri)).getOrElse(ContextEmpty)
  // format: on

  implicit val contextValueEncoder: Encoder[ContextValue] = Encoder.instance(_.value)
  implicit val contextValueDecoder: Decoder[ContextValue] = Decoder.decodeJson.map(apply)
}
