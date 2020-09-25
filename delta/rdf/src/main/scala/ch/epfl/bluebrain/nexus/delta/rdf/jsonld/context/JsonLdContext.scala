package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import io.circe.Json
import io.circe.syntax._

trait JsonLdContext extends Product with Serializable {
  type This >: this.type <: JsonLdContext

  /**
    * @return true if the current context value is empty, false otherwise
    */
  def isEmpty: Boolean =
    value == Json.obj() || value == Json.arr() || value == Json.fromString("")

  /**
    * Combines the current [[This]] context with a passed [[This]] context.
    * If a key inside the @context is repeated in both contexts, the one in ''that'' will override the current one.
    *
    * @param that another context to be merged with the current
    * @return the merged context
    */
  def merge(that: This): This

  /**
    * The value inside the key @context. It must be a Json Array or a Json Object
    */
  def value: Json

  /**
    * The context object. E.g.: {"@context": {...}}
    */
  def contextObj: Json =
    Json.obj(keywords.context -> value)

  /**
    * Add a prefix mapping to the current context.
    *
    * @param prefix the prefix that can be used to create curies
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addPrefix(prefix: String, iri: Iri): This

  /**
    * Add an alias to the current context.
    *
    * @param prefix the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAlias(prefix: String, iri: Iri): This =
    addAlias(prefix, iri, None)

  /**
    * Add an alias to the current context.
    *
   * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    * @param dataType the @type Iri value
    */
  def addAlias(prefix: String, iri: Iri, dataType: Iri): This =
    addAlias(prefix, iri, Some(dataType.toString))

  /**
    * Add an alias to the current context with the @type = @id.
    *
    * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAliasIdType(prefix: String, iri: Iri): This =
    addAlias(prefix, iri, Some(keywords.id))

  protected def addAlias(prefix: String, iri: Iri, dataType: Option[String]): This

  protected def add(key: String, v: Json): Json                    =
    value.arrayOrObject(Json.obj(key -> v), arr => (arr :+ Json.obj(key -> v)).asJson, _.add(key, v).asJson)

  protected def expandedTermDefinition(dt: String, iri: Iri): Json =
    Json.obj(keywords.tpe -> dt.asJson, keywords.id -> iri.asJson)

}

object JsonLdContext {

  object keywords {
    val context = "@context"
    val id      = "@id"
    val tpe     = "@type"
    val base    = "@base"
    val vocab   = "@vocab"
    val graph   = "@graph"
    val value   = "@value"
  }

  /**
    * @return the value of the top @context key when found, an empty Json otherwise
    */
  def topContextValueOrEmpty(json: Json): Json =
    json
      .arrayOrObject(
        None,
        arr => arr.singleEntryOr(Json.obj()).flatMap(_.asObject).flatMap(_(keywords.context)),
        obj => obj(keywords.context)
      )
      .getOrElse(Json.obj())

  /**
    * @return the all the values with key @context
    */
  def contextValues(json: Json): Set[Json] =
    json.extractValuesFrom(keywords.context)

  /**
    * Merges the values of the key @context in both passed ''json'' and ''that'' Json documents.
    *
    * @param json the primary context. E.g.: {"@context": {...}}
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original json and the merged context of both passed jsons.
    *         If a key inside the @context is repeated in both jsons, the one in ''that'' will override the one in ''json''
    */
  def addContext(json: Json, that: Json): Json =
    json deepMerge Json.obj(keywords.context -> merge(topContextValueOrEmpty(json), topContextValueOrEmpty(that)))

  /**
    * Adds a context Iri to an existing @context, or creates an @context with the Iri as a value.
    */
  def addContext(json: Json, contextIri: Iri): Json = {
    val jUriString = Json.fromString(contextIri.toString)

    json.asObject match {
      case Some(obj) =>
        val updated = obj(keywords.context) match {
          case None           => obj.add(keywords.context, jUriString)
          case Some(ctxValue) =>
            (ctxValue.asObject, ctxValue.asArray, ctxValue.asString) match {
              case (Some(co), _, _) if co.isEmpty                         => obj.add(keywords.context, jUriString)
              case (_, Some(ca), _) if ca.isEmpty                         => obj.add(keywords.context, jUriString)
              case (_, _, Some(cs)) if cs.isEmpty                         => obj.add(keywords.context, jUriString)
              case (Some(co), _, _) if !co.values.exists(_ == jUriString) =>
                obj.add(keywords.context, Json.arr(ctxValue, jUriString))
              case (_, Some(ca), _) if !ca.contains(jUriString)           =>
                obj.add(keywords.context, Json.fromValues(ca :+ jUriString))
              case (_, _, Some(cs)) if cs != contextIri.toString          =>
                obj.add(keywords.context, Json.arr(ctxValue, jUriString))
              case _                                                      => obj
            }
        }
        Json.fromJsonObject(updated)
      case None      => json
    }
  }

  /**
    * Merge two context value objects.
    * If a key inside is repeated in both passed jsons, the one in ''that'' will override the one in ''json''
    *
    * @param json the value of the @context key
    * @param that the value of the @context key
    */
  def merge(json: Json, that: Json): Json =
    (json.asArray, that.asArray, json.asString, that.asString) match {
      case (Some(arr), Some(thatArr), _, _) => arrOrObj(removeEmpty(arr ++ thatArr))
      case (_, Some(thatArr), _, _)         => arrOrObj(removeEmpty(json +: thatArr))
      case (Some(arr), _, _, _)             => arrOrObj(removeEmpty(arr :+ that))
      case (_, _, Some(str), Some(thatStr)) => arrOrObj(removeEmpty(Seq(str.asJson, thatStr.asJson)))
      case (_, _, Some(str), _)             => arrOrObj(removeEmpty(Seq(str.asJson, that)))
      case (_, _, _, Some(thatStr))         => arrOrObj(removeEmpty(Seq(json, thatStr.asJson)))
      case _                                => json deepMerge that
    }

  private def removeEmpty(arr: Seq[Json]): Seq[Json] =
    arr.filter(j => j != Json.obj() && j != Json.fromString("") && j != Json.arr())

  private def arrOrObj(arr: Seq[Json]): Json =
    arr.singleEntryOr(Json.obj()).getOrElse(Json.fromValues(arr))
}
