package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.{Json, JsonObject}
import io.circe.syntax._

/**
  * The Json value of the @context key
  */
final case class ContextValue private[jsonld] (value: Json) {

  override def toString: String = value.noSpaces

  private val emptyJson = Set(Json.obj(), Json.arr(), Json.Null, Json.fromString(""))

  /**
    * Combines the current [[ContextValue]] context with a passed [[ContextValue]] context.
    * If a keys are is repeated in both contexts, the one in ''that'' will override the current one.
    *
    * @param that another context to be merged with the current
    * @return the merged context
    */
  def merge(that: ContextValue): ContextValue =
    (value.asArray, that.value.asArray, value.asString, that.value.asString) match {
      case (Some(arr), Some(thatArr), _, _) => arrOrObj(removeEmptyAndDup(arr ++ thatArr))
      case (_, Some(thatArr), _, _)         => arrOrObj(removeEmptyAndDup(value +: thatArr))
      case (Some(arr), _, _, _)             => arrOrObj(removeEmptyAndDup(arr :+ that.value))
      case (_, _, Some(str), Some(thatStr)) => arrOrObj(removeEmptyAndDup(Seq(str.asJson, thatStr.asJson)))
      case (_, _, Some(str), _)             => arrOrObj(removeEmptyAndDup(Seq(str.asJson, that.value)))
      case (_, _, _, Some(thatStr))         => arrOrObj(removeEmptyAndDup(Seq(value, thatStr.asJson)))
      case _                                => ContextValue(value deepMerge that.value)
    }

  /**
    * @return true if the current context value is empty, false otherwise
    */
  def isEmpty: Boolean =
    emptyJson.contains(value)

  /**
    * The context object. E.g.: {"@context": {...}}
    */
  def contextObj: JsonObject                               =
    if (isEmpty) JsonObject.empty
    else JsonObject(keywords.context -> value)

  private def removeEmptyAndDup(arr: Seq[Json]): Seq[Json] =
    arr.filterNot(emptyJson.contains).distinct

  private def arrOrObj(arr: Seq[Json]): ContextValue =
    ContextValue(arr.singleEntryOr(Json.obj()).getOrElse(Json.fromValues(arr)))
}

object ContextValue {

  /**
    * An empty [[ContextValue]]
    */
  val empty: ContextValue = ContextValue(Json.obj())

  /**
    * Construct a [[ContextValue]] from remote context [[Iri]]s.
    */
  def apply(iri: Iri*): ContextValue =
    iri.toList match {
      case Nil         => empty
      case head :: Nil => ContextValue(head.asJson)
      case rest        => ContextValue(rest.asJson)
    }

  /**
    * Unsafely constructs a [[ContextValue]] from the passed json.
    *
    * @params json a json which is expected to be the Json value inside the @context key
    */
  def unsafe(json: Json): ContextValue =
    ContextValue(json)
}
