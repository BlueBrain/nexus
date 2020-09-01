package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.utils.SeqUtils.headOnlyOption
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.iri.IRI

trait JsonLdContext extends Product with Serializable {
  type This >: this.type <: JsonLdContext

  /**
    * The value of the key @context. It must be a Json Array or a Json Object
    */
  def value: Json

  /**
    * Add a prefix mapping to the current context.
    *
   * @param prefix the prefix that can be used to create curies
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addPrefix(prefix: String, iri: IRI): This

  /**
    * Add an alias to the current context.
    *
   * @param prefix the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri    the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAlias(prefix: String, iri: IRI): This =
    addAlias(prefix, iri, None)

  /**
    * Add an alias to the current context.
    *
   * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    * @param dataType the @type IRI value
    */
  def addAlias(prefix: String, iri: IRI, dataType: IRI): This =
    addAlias(prefix, iri, Some(dataType.toString))

  /**
    * Add an alias to the current context with the @type = @id.
    *
   * @param prefix  the prefix which replces the ''iri'' when compacting JSON-LD
    * @param iri     the iri which replaces the ''prefix'' when expanding JSON-LD
    */
  def addAliasIdType(prefix: String, iri: IRI): This =
    addAlias(prefix, iri, Some(keywords.id))

  protected def addAlias(prefix: String, iri: IRI, dataType: Option[String]): This

  protected def add(key: String, v: Json): Json                    =
    value.arrayOrObject(Json.obj(key -> v), arr => (arr :+ Json.obj(key -> v)).asJson, _.add(key, v).asJson)

  protected def expandedTermDefinition(dt: String, iri: IRI): Json =
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
    * @return the inner Json with the top @context key or None when not found
    */
  def topContextValue(json: Json): Option[Json] =
    json.arrayOrObject(
      None,
      arr => headOnlyOption(arr).flatMap(_.asObject).flatMap(_(keywords.context)),
      obj => obj(keywords.context)
    )

  /**
    * @return the all the values with key @context
    */
  def contextValues(json: Json): Set[Json] =
    json.extractValuesFrom(keywords.context)

  /**
    * @return the inner Json with the top @context key or and empty Json
    */
  def topContextValueOr(json: Json, default: => Json = Json.obj()): Json =
    topContextValue(json).getOrElse(default)

  /**
    * Merges the values of the key @context in both passed ''json'' and ''that'' Json documents.
    *
   * @param json the primary context. E.g.: {"@context": {...}}
    * @param that the context to append to this json. E.g.: {"@context": {...}}
    * @return a new Json with the original json and the merged context of both passed jsons.
    *         If a key inside the @context is repeated in both jsons, the one in ''that'' will override the one in ''json''
    */
  def addContext(json: Json, that: Json): Json =
    json deepMerge Json.obj(keywords.context -> merge(topContextValueOr(json), topContextValueOr(that)))

  /**
    * Adds a context IRI to an existing JSON object.
    */
  def addContext(json: Json, contextIri: IRI): Json = {
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

  private def removeEmpty(arr: Seq[Json]): Seq[Json] =
    arr.filter(j => j != Json.obj() && j != Json.fromString("") && j != Json.arr())

  private def arrOrObj(arr: Seq[Json]): Json =
    arr.take(2).toList match {
      case Nil         => Json.obj()
      case head :: Nil => head
      case _ :: _      => Json.arr(arr: _*)
    }

  private def merge(json: Json, that: Json): Json =
    (json.asArray, that.asArray, json.asString, that.asString) match {
      case (Some(arr), Some(thatArr), _, _) => arrOrObj(removeEmpty(arr ++ thatArr))
      case (_, Some(thatArr), _, _)         => arrOrObj(removeEmpty(json +: thatArr))
      case (Some(arr), _, _, _)             => arrOrObj(removeEmpty(arr :+ that))
      case (_, _, Some(str), Some(thatStr)) => arrOrObj(removeEmpty(Seq(str.asJson, thatStr.asJson)))
      case (_, _, Some(str), _)             => arrOrObj(removeEmpty(Seq(str.asJson, that)))
      case (_, _, _, Some(thatStr))         => arrOrObj(removeEmpty(Seq(json, thatStr.asJson)))
      case _                                => json deepMerge that
    }
}
