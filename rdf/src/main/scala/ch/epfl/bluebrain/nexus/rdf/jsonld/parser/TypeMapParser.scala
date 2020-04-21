package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.TypeMap
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri.KeywordValue
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, NodeObject}
import io.circe.{Json, JsonObject}

private class TypeMapParser private (obj: JsonObject, override val cursor: TermDefinitionCursor) extends JsonLDParser {

  private lazy val isVocab = cursor.value.exists(_.tpe.contains(KeywordValue(vocab)))

  final def parse(): Either[ParsingStatus, TypeMap] =
    obj.toList.foldM(TypeMap()) {
      case (acc, (_, json)) if json.isNull => Right(acc)

      case (TypeMap(accMap, accNone), (term, json)) if isAlias(term, keyword.none) =>
        NodeObjectParser(json, cursor).map(node => TypeMap(accMap, accNone :+ node))

      case (TypeMap(accMap, accNone), (typeTerm, json)) =>
        for {
          tpe      <- expand(typeTerm)
          node     <- parseTypeNodeObject(json, cursor.down(typeTerm))
          nodeType = node.copy(types = node.types :+ tpe)
        } yield TypeMap(accMap + (tpe -> nodeType), accNone)
    }

  private def parseTypeNodeObject(json: Json, innerCursor: => TermDefinitionCursor) =
    json.asString match {
      case Some(str) if isVocab => expand(str).map(id => NodeObject(Some(id)))
      case Some(str)            => expandId(str).map(id => NodeObject(Some(id)))
      case None                 => NodeObjectParser(json, innerCursor)
    }
}

object TypeMapParser {

  def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, TypeMap] =
    if (json.isNull)
      Left(NullObject)
    else if (cursor.value.exists(d => d.container == Set(tpe) || d.container == Set(tpe, set)))
      json.asObject.toRight(NotMatchObject).flatMap(obj => new TypeMapParser(obj, cursor).parse())
    else
      Left(NotMatchObject)
}
