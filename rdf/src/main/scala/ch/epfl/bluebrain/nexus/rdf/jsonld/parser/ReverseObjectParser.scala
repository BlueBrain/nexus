package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.NodeObjectArray
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.{IndexMap, SetValue, WrappedNodeObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe.Json

private class ReverseObjectParser private (json: Json, override val cursor: TermDefinitionCursor) extends JsonLDParser {

  private lazy val isIndex = cursor.value.exists(_.container.contains(index))

  final def parse(): Either[ParsingStatus, Seq[NodeObject]] =
    if (isIndex)
      IndexMapParser(json, cursor).flatMap(toNodeSet) onNotMatched Left(invalidReverseInnerValue)
    else
      ArrayObjectParser.set(json, cursor).flatMap(toNodeSet) onNotMatched Left(invalidReverseInnerValue)

  private def toNodeSet(result: SetValue) =
    result.value.toList.foldM(Vector.empty[NodeObject]) {
      case (acc, NodeObjectArray(node)) => Right(acc :+ node)
      case _                            => Left(invalidReverseInnerValue)
    }

  private def toNodeSet(result: IndexMap) =
    result.value.values.toList.foldM(Vector.empty[NodeObject]) {
      case (acc, WrappedNodeObject(node)) => Right(acc :+ node)
      case _                              => Left(invalidReverseValue)
    }
}

object ReverseObjectParser {

  /**
    * Attempts to fetch the Node Objects from a term which has @reverse container
    */
  def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, Seq[NodeObject]] =
    new ReverseObjectParser(json, cursor).parse()

}
