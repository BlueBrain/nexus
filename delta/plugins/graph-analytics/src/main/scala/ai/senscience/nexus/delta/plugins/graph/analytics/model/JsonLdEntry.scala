package ai.senscience.nexus.delta.plugins.graph.analytics.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

sealed trait JsonLdEntry extends Product with Serializable {

  def path: Seq[Iri]

  def isInArray: Boolean

}

object JsonLdEntry {

  private val pathSeparator = " / "

  sealed trait LiteralEntry                                         extends JsonLdEntry
  final case class BooleanEntry(path: Seq[Iri], isInArray: Boolean) extends LiteralEntry
  final case class NumberEntry(path: Seq[Iri], isInArray: Boolean)  extends LiteralEntry
  final case class StringEntry(tpe: Option[String], language: Option[String], path: Seq[Iri], isInArray: Boolean)
      extends LiteralEntry
  final case class EmptyEntry(path: Seq[Iri], isInArray: Boolean)   extends LiteralEntry

  final case class ObjectEntry(id: Option[Iri], types: Option[Set[Iri]], path: Seq[Iri], isInArray: Boolean)
      extends JsonLdEntry

  object ObjectEntry {
    implicit val objectEntryEncoder: Encoder.AsObject[ObjectEntry] = Encoder.AsObject.instance { o =>
      JsonObject(
        "path"      -> o.path.mkString(pathSeparator).asJson,
        "isInArray" -> o.isInArray.asJson,
        "dataType"  -> "object".asJson
      ).addIfNonEmpty(keywords.id, o.id)
        .addIfNonEmpty(keywords.tpe, o.types)
    }
  }

  /**
    * Create a literal entry if the json object is a value object
    * @param obj
    *   the object to parse
    */
  def literal(obj: JsonObject, path: Seq[Iri], isInArray: Boolean): Option[LiteralEntry] =
    obj(keywords.value).flatMap { json =>
      Option
        .when(json.isBoolean)(BooleanEntry(path, isInArray))
        .orElse(
          Option
            .when(json.isNumber)(NumberEntry(path, isInArray))
            .orElse(
              Option
                .when(json.isString)(
                  StringEntry(
                    obj(keywords.tpe).flatMap(_.asString),
                    obj(keywords.language).flatMap(_.asString),
                    path,
                    isInArray
                  )
                )
                .orElse(
                  Option.when(json.isNull)(
                    EmptyEntry(path, isInArray)
                  )
                )
            )
        )
    }

  implicit val jsonLdEntryEncoder: Encoder.AsObject[JsonLdEntry] = Encoder.AsObject.instance { e =>
    val default = JsonObject("path" -> e.path.mkString(pathSeparator).asJson, "isInArray" -> e.isInArray.asJson)
    e match {
      case o: ObjectEntry  => o.asJsonObject
      case _: BooleanEntry => default.add("dataType", "boolean".asJson)
      case _: NumberEntry  => default.add("dataType", "number".asJson)
      case _: EmptyEntry   => default.add("dataType", "empty".asJson)
      case s: StringEntry  =>
        default
          .add("dataType", "string".asJson)
          .addIfNonEmpty(keywords.tpe, s.tpe)
          .addIfNonEmpty(keywords.language, s.language)
    }
  }

}
