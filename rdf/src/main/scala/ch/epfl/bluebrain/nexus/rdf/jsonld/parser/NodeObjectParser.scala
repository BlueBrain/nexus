package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.NodeObjectArray
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NoneNullOr.Val
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinition.KeywordOrUri.KeywordValue
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.{Context, ContextWrapper, TermDefinitionCursor}
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, NodeObject, NoneNullOr}
import io.circe.syntax._
import io.circe.{Json, JsonObject}

import scala.annotation.tailrec

private class NodeObjectParser private (
    obj: JsonObject,
    override val cursor: TermDefinitionCursor,
    reverseParent: Boolean
) extends JsonLDParser {

  private lazy val notAllowedKeys = keyAliases(value) ++ keyAliases(list) ++ keyAliases(set)
  private lazy val typeKeys       = keyAliases(tpe)
  private lazy val idKeys         = keyAliases(id)
  private lazy val isVocab        = cursor.value.exists(_.tpe.contains(KeywordValue(vocab)))

  final def parse(): Either[ParsingStatus, NodeObject] =
    for {
      _      <- validate
      types  <- traverseTypes
      node   <- traverseMap(NodeObject(types = types))
      nodeId <- parseId(node.emptyValues, types)
    } yield node.copy(id = nodeId)

  private def validate =
    if (obj.keys.toSet.exists(notAllowedKeys.contains)) Left(NotMatchObject)
    else Right(())

  private def traverseTypes =
    obj.filterKeys(typeKeys.contains).toList.foldM(Vector.empty[Uri]) {
      case (acc, (_, json)) => parseType(json).map(acc ++ _)
    }
//    }.map(_.sortBy(_.iriString)(Ordering.String.reverse))

  private def traverseMap(init: NodeObject) =
    obj.remove(context).filterKeys(!(typeKeys ++ idKeys).contains(_)).toList.foldM(init) {
      case (node, (term, _)) if node.graph.nonEmpty && isAlias(term, graph) => Left(collidingKey(graph))
      case (node, (term, _)) if node.index.nonEmpty && isAlias(term, index) => Left(collidingKey(index))

      case (node, (term, json)) if isAlias(term, reverse) =>
        NodeObjectParser
          .reverse(json, cursor)
          .map(reversed => node.copy(terms = node.terms ++ reversed.terms, reverse = node.reverse ++ reversed.reverse))

      case (node, (term, json)) if isAlias(term, graph) =>
        parseGraphOrInclude(json).map(v => node.copy(graph = node.graph ++ v))

      case (node, (term, json)) if isAlias(term, included) =>
        parseGraphOrInclude(json).map(v => node.copy(included = node.included ++ v))

      case (node, (term, json)) if isAlias(term, index) =>
        asString(json, index).map(v => node.copy(index = Some(v)))

      case (_, (term, _)) if all.exists(k => isAlias(term, k)) => Left(invalidNodeObject(term))

      case (node, (term, json)) => parseTerm(node, term, json)

      case (acc, _) => Right(acc) // ignore when term is not an alias and it is not a resolvable Uri
    }

  private def parseTerm(node: NodeObject, term: String, json: Json) = {
    val innerCursor = cursor.down(term, node.types)

    def addReverseToNode(uri: Uri) =
      ReverseObjectParser(json, innerCursor).map(n => node.copy(reverse = node.reverse :+ (uri -> n)))

    def addTermToNode(uri: Uri) =
      parseNodeObjectValue match {
        case Left(NullObject) => Right(node)
        case Right(value)     => Right(node.copy(terms = node.terms :+ (uri -> value)))
        case Left(err)        => Left(err)
      }

    def parseNodeObjectValue = {
      lazy val valueObj = ValueObjectParser(json, innerCursor)
      lazy val nodeObj  = NodeObjectParser(json, innerCursor).map(WrappedNodeObject)
      lazy val listObj  = ArrayObjectParser.list(json, innerCursor)
      lazy val indexObj = IndexMapParser(json, innerCursor)
      lazy val setObj   = ArrayObjectParser.set(json, innerCursor)
      lazy val idObj    = IdMapParser(json, innerCursor)
      lazy val tpeObj   = TypeMapParser(json, innerCursor)
      lazy val langObj  = LanguageMapParser(json, innerCursor)
      lazy val jsonObj  = JsonObjectParser(json, innerCursor)
      val result =
        if (innerCursor.value.exists(_.tpe.contains(KeywordValue(keyword.json))))
          Right(JsonWrapper(json))
        else
          innerCursor.value.map(_.container) match {
            case Val(container) if container.contains(id) =>
              idObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj
            case Val(container) if container.contains(tpe) =>
              tpeObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj
            case Val(container) if container.contains(language) =>
              langObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj
            case Val(container) if container.contains(index) =>
              indexObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj onNotMatched listObj onNotMatched setObj
            case Val(container) if container.contains(list) =>
              listObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj
            case Val(container) if container.contains(set) =>
              setObj onNotMatched jsonObj onNotMatched valueObj onNotMatched nodeObj
            case _ =>
              jsonObj onNotMatched valueObj onNotMatched nodeObj onNotMatched listObj onNotMatched setObj
          }
      result.map {
        case WrappedNodeObject(n) if innerCursor.value.exists(_.container.contains(graph)) =>
          WrappedNodeObject(NodeObject(graph = Vector(n)))
        case WrappedNodeObject(n) if innerCursor.value.exists(_.container.contains(included)) =>
          WrappedNodeObject(NodeObject(included = Vector(n)))
        case other => other
      }
    }

    innerCursor.value.toOption.flatMap(_.reverse) match {
      case Some(uri) if !reverseParent => addReverseToNode(uri)
      case Some(uri)                   => addTermToNode(uri)
      case None =>
        expand(term, innerCursor) match {
          case Right(uri) if reverseParent => addReverseToNode(uri)
          case Right(uri)                  => addTermToNode(uri)
          case _                           => Right(node)
        }
    }
  }

  private def parseGraphOrInclude(json: Json) =
    ArrayObjectParser
      .set(json, cursor.downEmpty)
      .map(_.value.collect { case NodeObjectArray(n) if n.nonEmptyValues => n }) onNotMatched
      NodeObjectParser(json, cursor.downEmpty).map { case n if n.nonEmptyValues => Vector(n) }

  private def parseType(json: Json) =
    (json.asString, json.asArray) match {
      case (Some(str), _) => expand(str).map(Vector(_))
      case (_, Some(arr)) => arr.foldM(Vector.empty[Uri])((acc, c) => asString(c, tpe).flatMap(expand(_)).map(acc :+ _))
      case _              => Left(invalidJsonValue(tpe))
    }

  private def parseId(emptyNode: Boolean, types: Seq[Uri]): Either[ParsingStatus, Option[Uri]] =
    obj.filterKeys(idKeys.contains).toList.foldM(None: Option[Uri]) {
      case (None, (term, json)) =>
        val idCursor = cursor.downId(types, revertOnTypeScoped = !emptyNode)
        asString(json, term).flatMap {
          case str if isVocab => expand(str, idCursor).map(Some(_))
          case str            => expandId(str, idCursor).map(Some(_))
        }
      case _ => Left(collidingKey(id))
    }

}

object NodeObjectParser {

  final def root(json: Json, ctx: NoneNullOr[Context]): Either[ParsingStatus, NodeObject] =
    if (json.isNull) Right(NodeObject())
    else
      json.arrayOrObjectSingle(or = Right(NodeObject()), _ => Right(NodeObject()), obj => root(obj, ctx))

  final def root(obj: JsonObject, ctx: NoneNullOr[Context]): Either[ParsingStatus, NodeObject] =
    new NodeObjectParser(obj, TermDefinitionCursor.fromCtx(ctx), reverseParent = false).parse() match {
      case Left(NotMatchObject) | Left(NullObject) => Right(NodeObject())
      case other                                   => other
    }

  final def apply(
      json: Json,
      cursor: TermDefinitionCursor,
      reverseParent: Boolean = false
  ): Either[ParsingStatus, NodeObject] =
    if (json.isNull)
      Left(NullObject)
    else
      json.arrayOrObjectSingle(
        or = fromString(json, cursor).flatMap(applyObj(_, cursor, reverseParent)),
        _ => Left(NotMatchObject),
        obj => applyObj(obj, cursor, reverseParent)
      )

  @tailrec
  private def fromString(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, JsonObject] = {
    val allowedTypes: Set[KeywordOrUri] = Set(KeywordValue(id), KeywordValue(vocab))
    (json.asString, json.asArray) match {
      case (Some(str), _) if cursor.value.exists(_.tpe.exists(allowedTypes.contains)) =>
        Right(JsonObject(id -> str.asJson))
      case (_, Some(head +: IndexedSeq())) => fromString(head, cursor)
      case _                               => Left(NotMatchObject)
    }
  }

  final private[jsonld] def reverse(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, NodeObject] =
    json.asObject
      .toRight(invalidReverseValue)
      .flatMap(obj => applyObj(obj, cursor, reverseParent = true))
      .flatMap {
        case node @ NodeObject(None, IndexedSeq(), None, _, IndexedSeq(), IndexedSeq(), None, None, _) => Right(node)
        case _                                                                                         => Left(invalidReverseTerm)
      }

  private def applyObj(
      obj: JsonObject,
      cursor: TermDefinitionCursor,
      reverseParent: Boolean
  ): Either[ParsingStatus, NodeObject] =
    innerCtx(obj.asJson, cursor).flatMap(mergedCursor => new NodeObjectParser(obj, mergedCursor, reverseParent).parse())

  private def innerCtx(json: Json, cursor: TermDefinitionCursor) =
    ContextWrapper.fromParent(cursor.context).decodeJson(json).leftMap(err => InvalidObjectFormat(err.message)).map {
      case ContextWrapper(inner) => cursor.mergeContext(inner)
    }

}
