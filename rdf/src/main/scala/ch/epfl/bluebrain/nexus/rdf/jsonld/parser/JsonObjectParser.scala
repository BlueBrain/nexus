package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.JsonWrapper
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus.{collidingKey, invalidValueObject, NotMatchObject}
import io.circe.{Json, JsonObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._

private class JsonObjectParser private (obj: JsonObject, override val cursor: TermDefinitionCursor)
    extends JsonLDParser {

  private lazy val typeKeys = keyAliases(tpe)
  private lazy val jsonKeys = keyAliases(json)

  def parse(): Either[ParsingStatus, JsonWrapper] =
    if (isJsonType)
      fetchValue().flatMap(_.toRight(NotMatchObject).map(JsonWrapper))
    else
      Left(NotMatchObject)

  private def isJsonType =
    obj.filterKeys(typeKeys.contains).values.take(2).toList match {
      case json :: Nil => json.asString.exists(jsonKeys.contains)
      case _           => false
    }

  private def fetchValue() =
    obj.toList.foldM[Either[ParsingStatus, *], Option[Json]](None) {
      case (Some(_), (term, _)) if isAlias(term, value)        => Left(collidingKey(value))
      case (v, (term, _)) if isAlias(term, tpe)                => Right(v)
      case (None, (term, json)) if isAlias(term, value)        => Right(Some(json))
      case (v, (_, json)) if json.isNull                       => Right(v)
      case (_, (term, _)) if all.exists(k => isAlias(term, k)) => Left(invalidValueObject(term))
      case (_, (term, _)) if expandKey(term).isRight           => Left(invalidValueObject(term))
      case (acc, _)                                            => Right(acc) // ignore when term is not an alias and it is not a resolvable Uri
    }
}

private[jsonld] object JsonObjectParser {
  final def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, JsonWrapper] =
    json.arrayOrObjectSingle(Left(NotMatchObject), _ => Left(NotMatchObject), apply(_, cursor))

  final def apply(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, JsonWrapper] =
    new JsonObjectParser(obj, cursor).parse()

}
