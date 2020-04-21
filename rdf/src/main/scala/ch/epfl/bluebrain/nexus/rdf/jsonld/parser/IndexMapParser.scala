package ch.epfl.bluebrain.nexus.rdf.jsonld.parser

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.jsonld.{keyword, NodeObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry.{ListValueWrapper, NodeObjectArray, NodeValueArray}
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.{ArrayEntry, NodeObjectValue}
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.TermDefinitionCursor
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import ch.epfl.bluebrain.nexus.rdf.jsonld.parser.ParsingStatus._
import ch.epfl.bluebrain.nexus.rdf.jsonld.syntax.all._
import io.circe.{Json, JsonObject}

private class IndexMapParser private (obj: JsonObject, override val cursor: TermDefinitionCursor) extends JsonLDParser {

//  private val innerDefinition        = definition.map(_.withContainer(Set.empty))
  private lazy val isGraph = cursor.value.exists(_.container.contains(graph))

  final def parse(): Either[ParsingStatus, IndexMap] =
    obj.toList.foldM(IndexMap()) {
      case (acc, (_, json)) if json.isNull => Right(acc)
      case (IndexMap(acc, others), (term, json)) if isAlias(term, keyword.none) =>
        parseEntryValue(json).map(v => IndexMap(acc, others :+ v))

      case (IndexMap(acc, others), (index, json)) =>
        parseEntryValue(json, index).map(v => IndexMap(acc + (index -> v), others))
    }

  private def parseEntryValue(json: Json): Either[ParsingStatus, NodeObjectValue] =
    ValueObjectParser(json, cursor) onNotMatched
      NodeObjectParser(json, cursor).map(nodeObjectHandleGraph) onNotMatched
      ArrayObjectParser.list(json, cursor).map(listHandleGraph) onNotMatched
      ArrayObjectParser.set(json, cursor).map(setHandleGraph)

  private def nodeObjectHandleGraph(result: NodeObject) =
    result match {
      case n if isGraph && n.graph.isEmpty => WrappedNodeObject(NodeObject(graph = Vector(n)))
      case n                               => WrappedNodeObject(n)
    }
  private def setHandleGraph(set: SetValue)    = set.copy(value = arrayHandleGraph(set.value))
  private def listHandleGraph(list: ListValue) = list.copy(value = arrayHandleGraph(list.value))

  private def arrayHandleGraph(seq: Seq[ArrayEntry]): Seq[ArrayEntry] =
    seq.map {
      case NodeObjectArray(n)                   => NodeObjectArray(nodeObjectHandleGraph(n).value)
      case ListValueWrapper(ListValue(sseq, _)) => ListValueWrapper(ListValue(arrayHandleGraph(sseq)))
      case other                                => other
    }

  private def parseEntryValue(json: Json, index: String): Either[ParsingStatus, NodeObjectValue] =
    ValueObjectParser(json, cursor).map(withIndex(_, index)) onNotMatched
      NodeObjectParser(json, cursor).map(withIndex(_, index)).map(nodeObjectHandleGraph(_, index)) onNotMatched
      ArrayObjectParser
        .list(json, cursor)
        .map(v => v.copy(value = withIndex(v.value, index)))
        .map(listHandleGraph(_, index)) onNotMatched
      ArrayObjectParser
        .set(json, cursor)
        .map(v => v.copy(value = withIndex(v.value, index)))
        .map(setHandleGraph(_, index))

  private def nodeObjectHandleGraph(result: NodeObject, index: String) =
    result match {
      case n if isGraph && n.graph.isEmpty =>
        WrappedNodeObject(NodeObject(graph = Vector(n.copy(index = None)), index = Some(index)))
      case n =>
        WrappedNodeObject(n)
    }

  private def setHandleGraph(set: SetValue, index: String)    = set.copy(value = arrayHandleGraph(set.value, index))
  private def listHandleGraph(list: ListValue, index: String) = list.copy(value = arrayHandleGraph(list.value, index))

  private def arrayHandleGraph(seq: Seq[ArrayEntry], index: String): Seq[ArrayEntry] =
    seq.map {
      case NodeObjectArray(n)                   => NodeObjectArray(nodeObjectHandleGraph(n, index).value)
      case ListValueWrapper(ListValue(sseq, _)) => ListValueWrapper(ListValue(arrayHandleGraph(sseq, index)))
      case other                                => other
    }

  private def withIndex(v: ValueObject, index: String): ValueObject = v.index.fold(v.copy(index = Some(index)))(_ => v)
  private def withIndex(v: NodeObject, index: String): NodeObject   = v.index.fold(v.copy(index = Some(index)))(_ => v)
  private def withIndex(values: Seq[ArrayEntry], index: String): Seq[ArrayEntry] =
    values.map {
      case NodeObjectArray(value) => NodeObjectArray(withIndex(value, index))
      case NodeValueArray(value)  => NodeValueArray(withIndex(value, index))
      case other                  => other
    }
}

object IndexMapParser {

  def apply(json: Json, cursor: TermDefinitionCursor): Either[ParsingStatus, IndexMap] =
    if (json.isNull)
      Left(NullObject)
    else if (cursor.value.exists(_.container.contains(index)))
      json.asObject.toRight(NotMatchObject).flatMap(apply(_, cursor))
    else
      Left(NotMatchObject)

  def apply(obj: JsonObject, cursor: TermDefinitionCursor): Either[ParsingStatus, IndexMap] =
    new IndexMapParser(obj, cursor).parse()

}
