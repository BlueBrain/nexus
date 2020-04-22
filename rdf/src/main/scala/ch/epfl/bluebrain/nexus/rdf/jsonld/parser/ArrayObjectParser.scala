package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.{ListValue, SetValue}
import ch.epfl.bluebrain.nexus.rdf.jsonld._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe.{Json, JsonObject}

private class ArrayParser private (json: Json, override val cursor: TermDefinitionCursor) extends JsonLDParser {

  private lazy val isGraph     = cursor.value.exists(_.container.contains(graph))
  private lazy val isIdOrIndex = cursor.value.exists(d => d.container.exists(Set(id, index).contains))

  final def parseList(): Either[ParsingStatus, ListValue] = {
    json.asArray match {
      case Some(arr) =>
        arr.foldM(ListValue())((acc, c) => parseList(c).map(list => acc.copy(value = acc.value ++ list.value)))
      case _ =>
        parseList(json)
    }
  }

  final def parseSet(): Either[ParsingStatus, SetValue] = parseSet(json)

  private def parseList(inner: Json): Either[ParsingStatus, ListValue] =
    inner.arrayOrObject(
      or = parseItemListNotArray(inner).map(ListValue(_)),
      arr =>
        if (arr.isEmpty)
          Right(ListValue(Vector(ListValueWrapper(ListValue()))))
        else
          arr.foldM(ListValue()) { (acc, c) =>
            parseList(c).map(list => acc.copy(value = acc.value :+ ListValueWrapper(list)))
          },
      obj =>
        parseItemListNotArray(inner).map(ListValue(_)) onNotMatched
          ArrayObjectParser.listObject(obj, cursor).map(v => ListValue(Vector(ListValueWrapper(v)))) onNotMatched
          ArrayObjectParser.setObject(obj, cursor).map(v => ListValue(Vector(ListValueWrapper(ListValue(v.value)))))
    )

  private def parseSet(inner: Json): Either[ParsingStatus, SetValue] =
    inner.arrayOrObjectSingle(
      or = parseItemListNotArray(inner).map(SetValue(_)),
      arr => arr.foldM(SetValue())((acc, c) => parseSet(c).map(set => acc.copy(value = acc.value ++ set.value))),
      obj =>
        parseItemListNotArray(inner).map(SetValue(_)) onNotMatched
          ArrayObjectParser.setObject(obj, cursor) onNotMatched
          ArrayObjectParser.listObject(obj, cursor).map(v => SetValue(Vector(ListValueWrapper(v))))
    )

  private def parseItemListNotArray(inner: Json) =
    (ValueObjectParser(inner, cursor).toOptionNull.map(_.map(NodeValueArray)) onNotMatched
      NodeObjectParser(inner, cursor).toOptionNull.map(_.map(handleGraph)) onNotMatched
      emptyOnNullArray(inner)).map(_.fold(Vector.empty[ArrayEntry])(Vector(_)))

  private def handleGraph(result: NodeObject) =
    result match {
      case n if (isGraph && isIdOrIndex && n.graph.isEmpty) || (isGraph && !isIdOrIndex) =>
        NodeObjectArray(NodeObject(graph = Vector(n.copy(index = None)), index = n.index))
      case n => NodeObjectArray(n)
    }

  private def emptyOnNullArray(inner: Json): Either[ParsingStatus, Option[ArrayEntry]] =
    inner.asArray match {
      case Some(arr) if arr.forall(_.isNull) => Right(None)
      case _                                 => Left(NotMatchObject)
    }
}

object ArrayParser {
  private def allowedSet = Set(keyword.set, keyword.graph, keyword.id, keyword.index)

  def list(
      json: Json,
      cursor: TermDefinitionCursor
  ): Either[ParsingStatus, ListValue] =
    if (json.isNull) Right(ListValue())
    else if (cursor.value.exists(_.container.contains(keyword.list))) listUnsafe(json, cursor)
    else Left(NotMatchObject)

  def set(
      json: Json,
      cursor: TermDefinitionCursor
  ): Either[ParsingStatus, SetValue] =
    if (json.isNull) Right(SetValue())
    else if (cursor.value.forall(d => d.container.exists(allowedSet.contains) || d.container.isEmpty))
      setUnsafe(json, cursor)
    else Left(NotMatchObject)

  private[jsonld] def listUnsafe(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, ListValue] =
    new ArrayParser(json, cursor).parseList()

  private[jsonld] def setUnsafe(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, SetValue] =
    new ArrayParser(json, cursor).parseSet()

}

private class ArrayObjectParser private (obj: JsonObject, override val cursor: TermDefinitionCursor)
    extends JsonLDParser {

  final def parseList(): Either[ParsingStatus, ListValue] =
    for {
      values                         <- fetchValues(list)
      (arrOption, index, otherTerms) = values
      arrJson                        <- arrOption.toRight(NotMatchObject)
      _                              <- Option.when(!otherTerms)(()).toRight(invalidListObject)
      result                         <- ArrayParser.listUnsafe(arrJson, cursor)
    } yield result.copy(index = index)

  final def parseSet(): Either[ParsingStatus, SetValue] =
    for {
      values                         <- fetchValues(set)
      (arrOption, index, otherTerms) = values
      arrJson                        <- arrOption.toRight(NotMatchObject)
      _                              <- Option.when(!otherTerms)(()).toRight(invalidSetObject)
      result                         <- ArrayParser.setUnsafe(arrJson, cursor)
    } yield result.copy(index = index)

  private def fetchValues(arrayKey: String) =
    obj.toList.foldM[Either[ParsingStatus, *], (Option[Json], Option[String], Boolean)]((None, None, false)) {
      case ((Some(_), _, _), (term, _)) if isAlias(term, arrayKey)         => Left(collidingKey(arrayKey))
      case ((_, Some(_), _), (term, _)) if isAlias(term, index)            => Left(collidingKey(index))
      case ((None, idx, b), (term, json)) if isAlias(term, arrayKey)       => Right((Some(json), idx, b))
      case ((arr, None, b), (term, json)) if isAlias(term, index)          => asString(json, index).map(v => (arr, Some(v), b))
      case (acc, (_, json)) if json.isNull                                 => Right(acc)
      case ((arr, idx, true), _)                                           => Right((arr, idx, true))
      case ((arr, idx, _), (term, _)) if all.exists(k => isAlias(term, k)) => Right((arr, idx, true))
      case ((arr, idx, _), (term, _)) if expand(term).isRight              => Right((arr, idx, true))
      case (acc, _)                                                        => Right(acc) // ignore when term is not an alias and it is not a resolvable Uri
    }
}

private[jsonld] object ArrayObjectParser {

  def list(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, ListValue] =
    if (json.isObject) listObject(json, cursor) onNotMatched ArrayParser.list(json, cursor)
    else ArrayParser.list(json, cursor) onNotMatched listObject(json, cursor)

  def set(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, SetValue] =
    ArrayParser.set(json, cursor) onNotMatched setObject(json, cursor)

  def listObject(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, ListValue] =
    if (json.isNull)
      Left(NullObject)
    else
      json.arrayOrObjectSingle(
        Left(NotMatchObject),
        _ => Left(NotMatchObject),
        obj => listObject(obj, cursor)
      )

  final def listObject(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, ListValue] =
    new ArrayObjectParser(obj, cursor).parseList()

  private[jsonld] def setObject(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, SetValue] =
    if (json.isNull)
      Left(NullObject)
    else
      json.arrayOrObjectSingle(
        Left(NotMatchObject),
        _ => Left(NotMatchObject),
        obj => setObject(obj, cursor)
      )

  final def setObject(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, SetValue] =
    new ArrayObjectParser(obj, cursor).parseSet()
}
