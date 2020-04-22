package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.ValueObject
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri.UriValue
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ContextParser.allowedDirection
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe.{Json, JsonObject}

import scala.annotation.tailrec

private class ValueObjectParser private (obj: JsonObject, override val cursor: TermDefinitionCursor)
    extends JsonLDParser {

  private type ValueObjectFields =
    (Option[Literal], Option[Uri], Option[LanguageTag], Option[String], Option[String], Boolean)

  final def parse(): Either[ParsingStatus, ValueObject] =
    fetchFields.flatMap {
      case (None, None, Some(_), _, _, _)                                   => Left(NullObject)
      case (None, _, _, _, _, _)                                            => Left(NotMatchObject)
      case (Some(_), Some(_), lang, d, _, _) if lang.nonEmpty || d.nonEmpty => Left(invalidValueObjectExclusion)
      case (Some(lit), None, lang, d, idx, _)                               => Right(ValueObject(lit.copy(languageTag = lang), false, d, idx))
      case (Some(lit), Some(t), lang, d, idx, _)                            => Right(ValueObject(Literal(lit.lexicalForm, t, lang), true, d, idx))
    }

  private def fetchFields =
    obj.toList.foldM[Either[ParsingStatus, *], ValueObjectFields]((None, None, None, None, None, false)) {
      case ((Some(_), _, _, _, _, _), (term, _)) if isAlias(term, value)     => Left(collidingKey(value))
      case ((_, Some(_), _, _, _, _), (term, _)) if isAlias(term, tpe)       => Left(collidingKey(tpe))
      case ((_, _, Some(_), _, _, _), (term, _)) if isAlias(term, language)  => Left(collidingKey(language))
      case ((_, _, _, Some(_), _, _), (term, _)) if isAlias(term, direction) => Left(collidingKey(direction))
      case ((_, _, _, _, Some(_), _), (term, _)) if isAlias(term, index)     => Left(collidingKey(index))
      case ((None, _, _, _, _, true), (term, _)) if isAlias(term, value)     => Left(invalidValueObject(term))

      case ((None, t, lang, dir, idx, false), (term, json)) if isAlias(term, value) =>
        parseValue(json).map(literal => (Some(literal), t, lang, dir, idx, false))

      case ((lit, None, lang, dir, idx, false), (term, json)) if isAlias(term, tpe) =>
        parseType(json).map(uri => (lit, Some(uri), lang, dir, idx, false))

      case ((lit, t, None, dir, idx, false), (term, json)) if isAlias(term, language) =>
        parseLanguage(json).map(lang => (lit, t, Some(lang), dir, idx, false))

      case ((lit, t, lang, None, idx, false), (term, json)) if isAlias(term, direction) =>
        parseDirection(json).map(direction => (lit, t, lang, Some(direction), idx, false))

      case ((lit, t, lang, dir, None, false), (term, json)) if isAlias(term, index) =>
        parseIndex(json).map(index => (lit, t, lang, dir, Some(index), false))

      case (acc, (_, json)) if json.isNull => Right(acc)
      case ((lit, t, lang, dir, idx, _), (term, _)) if all.exists(k => isAlias(term, k)) =>
        Option.when(lit.isEmpty)((lit, t, lang, dir, idx, true)).toRight(invalidValueObject(term))
      case ((lit, t, lang, dir, idx, _), (term, _)) if expandKey(term).isRight =>
        Option.when(lit.isEmpty)((lit, t, lang, dir, idx, true)).toRight(invalidValueObject(term))
      case (acc, _) => Right(acc) // ignore when term is not an alias and it is not a resolvable Uri
    }

  private def parseValue(json: Json) =
    (json.asNull, json.asBoolean, json.asString, json.asNumber) match {
      case (Some(_), _, _, _) =>
        Left(NullObject)
      case (_, Some(v), _, _) => Right(Literal(v))
      case (_, _, Some(v), _) => Right(Literal(v))
      case (_, _, _, Some(v)) =>
        Right(v.toInt.map(Literal(_)).orElse(v.toLong.map(Literal(_))).getOrElse(Literal(v.toDouble)))
      case _ => Left(invalidValue)
    }

  private def parseType(json: Json) =
    asString(json, tpe).flatMap(expand(_)).leftMap(_ => invalidTerm(tpe))

  private def parseIndex(json: Json) =
    asString(json, index)

  private def parseLanguage(json: Json) =
    asString(json, language).flatMap(LanguageTag(_).leftMap(InvalidObjectFormat))

  private def parseDirection(json: Json) =
    asString(json, direction).flatMap(s => Option.when(allowedDirection.contains(s))(s).toRight(invalidDirection(s)))

}

private[jsonld] object ValueObjectParser {

  final def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, ValueObject] =
    json.arrayOrObjectSingle(
      primitiveValue(json, cursor),
      _ => Left(NotMatchObject),
      obj => apply(obj, cursor)
    )

  final def apply(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, ValueObject] =
    new ValueObjectParser(obj, cursor).parse()

  private val idTypes = Set[KeywordOrUri](id, vocab)

  @tailrec
  private def primitiveValue(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, ValueObject] =
    if (json.isString && cursor.value.exists(_.tpe.exists(idTypes.contains)))
      Left(NotMatchObject)
    else {
      lazy val tpe = cursor.value.toOption.flatMap(_.tpe.collect { case UriValue(uri) => uri })
      (json.asBoolean, json.asString, json.asNumber, json.asArray, json.asNull) match {
        case (Some(v), _, _, _, _) => Right(ValueObject(Literal(v.toString, tpe.getOrElse(xsd.boolean)), tpe.nonEmpty))
        case (_, Some(v), _, _, _) =>
          val lang      = cursor.value.flatMap(_.termLanguage).toOption
          val direction = cursor.value.flatMap(_.termDirection).toOption
          Right(ValueObject(Literal(v, tpe.getOrElse(xsd.string), lang), tpe.nonEmpty, direction))
        case (_, _, Some(v), _, _) =>
          (v.toInt, v.toLong) match {
            case (Some(_), _) => Right(ValueObject(Literal(v.toString, tpe.getOrElse(xsd.int)), tpe.nonEmpty))
            case (_, Some(_)) => Right(ValueObject(Literal(v.toString, tpe.getOrElse(xsd.long)), tpe.nonEmpty))
            case (_, _)       => Right(ValueObject(Literal(v.toString, tpe.getOrElse(xsd.double)), tpe.nonEmpty))
          }
        case (_, _, _, Some(v), _) =>
          v match {
            case head +: IndexedSeq() if !head.isNull => primitiveValue(head, cursor)
            case _                                    => Left(NotMatchObject)
          }
        case (_, _, _, _, Some(_)) => Left(NullObject)

        case _ => Left(NotMatchObject)
      }
    }
}
