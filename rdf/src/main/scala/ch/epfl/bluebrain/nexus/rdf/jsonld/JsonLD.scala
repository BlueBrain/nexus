package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.JsonLD.{CompactedJsonLD, ExpandedJsonLD}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, ContextWrapper}
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.NodeObjectParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe._
import io.circe.syntax._

sealed trait JsonLD extends Product with Serializable {
  def original: Json
  def toJson(): Json
  def isCompact: Boolean
  def isExpanded: Boolean
  def compact: Either[String, CompactedJsonLD]
  def compact(context: Json): Either[String, CompactedJsonLD]
  def expand: Either[String, ExpandedJsonLD]
}

object JsonLD {
  final case class CompactedJsonLD(
      original: Json,
      private val context: EmptyNullOr[Context],
      private[jsonld] val node: NodeObject
  ) extends JsonLD {
    val isCompact: Boolean                                      = true
    val isExpanded: Boolean                                     = false
    val compact: Either[String, CompactedJsonLD]                = Right(this)
    def compact(context: Json): Either[String, CompactedJsonLD] = ???
    def expand: Either[String, ExpandedJsonLD]                  = Right(ExpandedJsonLD(original, context, node))
    def toJson(): Json                                          = ???
  }

  final case class ExpandedJsonLD(
      original: Json,
      private val context: EmptyNullOr[Context],
      private[jsonld] val node: NodeObject
  ) extends JsonLD {
    val isCompact: Boolean                                      = false
    val isExpanded: Boolean                                     = true
    def compact: Either[String, CompactedJsonLD]                = ???
    def compact(context: Json): Either[String, CompactedJsonLD] = ???
    val expand: Either[String, ExpandedJsonLD]                  = Right(this)
    def toJson(): Json                                          = Expansion(node)

  }

  private val wrongFormat = "Wrong JsonLD format"

  private def jsonld(json: Json, ctx: Json): Either[String, (Json, EmptyNullOr[Context], NodeObject)] =
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
  def compact(json: Json, context: Json = Json.obj()): Either[String, CompactedJsonLD] =
    jsonld(json, context).map { case (original, ctx, node) => CompactedJsonLD(original, ctx, node) }

  /**
    * Creates a Expanded JSON-LD document
    * @param json the passed JSON document to be interpreted as JSON-LD, which should include the "@context"
    */
  def expand(json: Json, context: Json = Json.obj()): Either[String, ExpandedJsonLD] =
    jsonld(json, context).map { case (original, ctx, node) => ExpandedJsonLD(original, ctx, node) }

}
