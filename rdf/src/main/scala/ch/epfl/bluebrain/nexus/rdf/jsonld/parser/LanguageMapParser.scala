package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.DirectionValue
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.LanguageMap
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import io.circe.{Json, JsonObject}

private class LanguageMapParser private (obj: JsonObject, override val cursor: TermDefinitionCursor)
    extends JsonLDParser {

  private lazy val termDirection = cursor.value.flatMap(_.termDirection).toOption

  final def parse(): Either[ParsingStatus, LanguageMap] =
    obj.toList.foldM(LanguageMap()) {
      case (acc, (_, json)) if json.isNull =>
        Right(acc)

      case (LanguageMap(accMap, accNone), (term, json)) if isAlias(term, keyword.none) =>
        parseEntryValue(json).map(directionStrings => LanguageMap(accMap, accNone ++ directionStrings))

      case (LanguageMap(accMap, accNone), (langStr, json)) =>
        parseEntry(langStr, json).map {
          case (lang, v) => LanguageMap(accMap.updatedWith(lang)(entry => Some(entry.fold(v)(_ ++ v))), accNone)
        }
    }

  private def parseEntry(lang: String, json: Json) =
    for {
      directionStrings <- parseEntryValue(json)
      language         <- LanguageTag(lang).leftMap(InvalidObjectFormat)
    } yield language -> directionStrings

  private def parseEntryValue(json: Json): Either[ParsingStatus, Seq[DirectionValue]] =
    (json.asString, json.asArray) match {
      case (Some(str), _) => Right(Vector((str, termDirection)))
      case (_, Some(vector)) =>
        vector.foldM(Vector.empty[DirectionValue]) {
          case (acc, c) if c.isNull => Right(acc)
          case (acc, c)             => c.asString.toRight(invalidLanguageMapValue).map(str => acc :+ ((str, termDirection)))
        }
      case _ => Left(invalidLanguageMapValue)
    }
}

private[jsonld] object LanguageMapParser {

  def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, LanguageMap] =
    if (json.isNull)
      Left(NullObject)
    else if (cursor.value.exists(d => d.container == Set(language) || d.container == Set(language, set)))
      json.asObject.toRight(NotMatchObject).flatMap(obj => new LanguageMapParser(obj, cursor).parse())
    else
      Left(NotMatchObject)
}
