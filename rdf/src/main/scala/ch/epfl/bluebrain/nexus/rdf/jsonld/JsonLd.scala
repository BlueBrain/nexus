package ch.epfl.bluebrain.nexus.rdf.jsonld

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.rdf.jsonld.EmptyNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, ContextWrapper}
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.NodeObjectParser
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe._
import io.circe.syntax._

final class JsonLd(private[jsonld] val node: NodeObject, orig: Json) {
  val original: Json                                 = orig
  def expanded: Json                                 = Expansion(node)
  def compacted(context: Json): Either[String, Json] = ???
  def toGraph: Graph                                 = ToGraph(node)
}

object JsonLd {

  private val wrongFormat = "Wrong JsonLD format"

  final def apply(json: Json, ctx: Json = Json.obj())(implicit options: JsonLdOptions): Either[String, JsonLd] = {
    json.arrayOrObjectSingle(
      Left(wrongFormat),
      _ => Left(wrongFormat),
      obj => {
        val jsonObj = if(ctx == Json.obj()) obj.asJson else obj.asJson deepMerge ctx
        for {
          wrapped <- jsonObj.as[ContextWrapper].leftMap(_.message)
          context = wrapped.`@context`.onEmpty(Val(Context(base = EmptyNullOr(options.base))))
          node    <- NodeObjectParser.root(obj.remove(keyword.context), context).leftMap(_.message)
        } yield new JsonLd(node, json)
      }
    )
  }
}
