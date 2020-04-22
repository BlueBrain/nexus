package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLd.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, ContextWrapper}
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.NodeObjectParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe._
import io.circe.syntax._

sealed trait JsonLd extends Product with Serializable {
  def original: Json
  def toJson(): Json
  def isCompact: Boolean
  def isExpanded: Boolean
  def compact: Either[String, CompactedJsonLd]
  def compact(context: Json): Either[String, CompactedJsonLd]
  def expand: Either[String, ExpandedJsonLd]
}

object JsonLd {
  final case class CompactedJsonLd(
      original: Json,
      private val context: EmptyNullOr[Context],
      private[jsonld] val node: NodeObject
  ) extends JsonLd {
    val isCompact: Boolean                                      = true
    val isExpanded: Boolean                                     = false
    val compact: Either[String, CompactedJsonLd]                = Right(this)
    def compact(context: Json): Either[String, CompactedJsonLd] = ???
    def expand: Either[String, ExpandedJsonLd]                  = Right(ExpandedJsonLd(original, context, node))
    def toJson(): Json                                          = ???
  }

  final case class ExpandedJsonLd(
      original: Json,
      private val context: EmptyNullOr[Context],
      private[jsonld] val node: NodeObject
  ) extends JsonLd {
    val isCompact: Boolean                                      = false
    val isExpanded: Boolean                                     = true
    def compact: Either[String, CompactedJsonLd]                = ???
    def compact(context: Json): Either[String, CompactedJsonLd] = ???
    val expand: Either[String, ExpandedJsonLd]                  = Right(this)
    def toJson(): Json                                          = Expansion(node)

  }

  private val wrongFormat = "Wrong JsonLD format"

  private def jsonld(json: Json, ctx: Json)(
      implicit options: JsonLdOptions
  ): Either[String, (Json, EmptyNullOr[Context], NodeObject)] =
    (json deepMerge ctx).arrayOrObjectSingle(
      Left(wrongFormat),
      _ => Left(wrongFormat),
      obj => {
        val jsonObj = obj.asJson
        for {
          wrapped <- jsonObj.as[ContextWrapper].leftMap(_.message)
          context = wrapped.`@context`
          node    <- NodeObjectParser.root(obj.remove(keyword.context), context).leftMap(_.message)
        } yield (jsonObj, context, node)
      }
    )

  /**
    * Creates a Compacted JSON-LD document
    * @param json the passed JSON document to be interpreted as JSON-LD
    * @param context the context to be applied to the json document. If the passed json already contains a @context field,
    *                this will be overridden. The expected context must be in the form of ''{"@context": {}}''
    */
  def compact(json: Json, context: Json = Json.obj())(
      implicit options: JsonLdOptions
  ): Either[String, CompactedJsonLd] =
    jsonld(json, context).map { case (original, ctx, node) => CompactedJsonLd(original, ctx, node) }

  /**
    * Creates a Expanded JSON-LD document
    * @param json the passed JSON document to be interpreted as JSON-LD, which should include the "@context"
    */
  def expand(json: Json, context: Json = Json.obj())(implicit options: JsonLdOptions): Either[String, ExpandedJsonLd] =
    jsonld(json, context).map { case (original, ctx, node) => ExpandedJsonLd(original, ctx, node) }

}
