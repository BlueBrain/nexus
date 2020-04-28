package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.{ArrayEntry, NodeObjectValue, TermNodeObject, TermValue}
import ch.epfl.bluebrain.nexus.rdf.jsonld.keyword._
import io.circe.syntax._
import io.circe.{Json, JsonObject}

import scala.collection.immutable.{SortedMap, VectorMap}

private[jsonld] object Expansion {

  final def apply(node: NodeObject): Json = expand(node, root = true)

  private def expand(elem: NodeObject, root: Boolean = false): Json =
    elem match {
      case NodeObject(_, IndexedSeq(), _, IndexedSeq(), graph, IndexedSeq(), _, None, IndexedSeq()) if root =>
        if (graph.exists(_.nonEmptyValues)) expandNodes(graph) else Json.arr()
      case NodeObject(uri, types, _, termsRev, graphSeq, includeSeq, _, idx, terms) =>
        val obj = JsonObject.fromMap(
          VectorMap.empty[String, Json] ++
            uri.map(id                                -> _.iriString.asJson) ++
            Option.when(types.nonEmpty)(tpe           -> Json.fromValues(types.map(_.iriString.asJson))) ++
            idx.map(index                             -> _.asJson) ++
            Option.when(graphSeq.nonEmpty)(graph      -> expandNodes(graphSeq)) ++
            Option.when(includeSeq.nonEmpty)(included -> expandNodes(includeSeq)) ++
            Option.when(termsRev.nonEmpty)(reverse    -> expandReverse(termsRev)) ++
            expandTerms(terms)
        )
        if (root) Json.arr(obj.asJson)
        else obj.asJson

    }

  private def expandReverse(reverse: Seq[TermNodeObject]) =
    JsonObject.fromMap(expandTerms(reverse.map { case (uri, v) => uri -> SetValue(v.map(NodeObjectArray)) })).asJson

  private def expandTerms(terms: Seq[TermValue]) =
    terms.foldLeft(VectorMap.empty[String, Json]) {
      case (acc, (term, v)) =>
        acc.updatedWith(term.iriString)(_.fold(Option(expand(v)))(accJson => Some(mergeJson(accJson, expand(v)))))
    }

  private def mergeJson(left: Json, right: Json): Json =
    (left.asArray, right.asArray) match {
      case (Some(leftArr), Some(rightArr)) => Json.fromValues(leftArr ++ rightArr)
      case (_, _) if left.isNull           => right
      case (_, _) if right.isNull          => left
      case (Some(leftArr), _)              => Json.fromValues(leftArr :+ right)
      case (_, Some(rightArr))             => Json.fromValues(left +: rightArr)
      case _                               => Json.arr(left, right)
    }

  private def expand(elem: ValueObject): Json =
    elem match {
      case ValueObject(l @ Literal(_, _, lang), explicitType, direct, idx) =>
        JsonObject
          .fromMap(
            VectorMap(value        -> toJson(l)) ++
              explicitType.map(tpe -> _.asJson) ++
              lang.map(language    -> _.asJson) ++
              direct.map(direction -> _.asJson) ++
              idx.map(index        -> _.asJson)
          )
          .asJson
    }

  private def expand(array: SetValue): Json =
    expandArr(array.value)

  private def expand(array: ListValue): Json =
    Json.obj(list -> expandArr(array.value))

  private def expandArr(iter: Iterable[ArrayEntry]): Json = {
    Json.fromValues(iter.map {
      case NodeObjectArray(node)   => expand(node)
      case NodeValueArray(value)   => expand(value)
      case ListValueWrapper(value) => expand(value)
    })
  }

  private def expand(elem: NodeObjectValue): Json =
    elem match {
      case WrappedNodeObject(value) => Json.arr(expand(value))
      case v: ValueObject           => Json.arr(expand(v))
      case v: LanguageMap           => expandLangs(v)
      case v: SetValue              => expand(v)
      case v: ListValue             => Json.arr(expand(v))
      case TypeMap(value, others)   => expandNodes(value.values.toSeq ++ others)
      case IdMap(value, others)     => expandNodes(value.values.toSeq.flatten ++ others)
      case JsonWrapper(json)        => Json.arr(Json.obj(value -> json, tpe -> keyword.json.asJson))
      case IndexMap(value, others) =>
        value.foldLeft(fromValues(others.map(expand))) {
          case (acc, (idx, v)) => mergeJson(acc, expand(withIndex(idx, v)))
        }
    }

  private def fromValues(iter: Iterable[Json]) =
    iter.toList match {
      case head :: Nil => head
      case _           => Json.fromValues(iter)
    }

  private def expandNodes(seq: Seq[NodeObject]): Json =
    Json.fromValues(seq.map(expand(_)))

  private def expandLangs(elems: LanguageMap): Json = {
    val valuesWithLang =
      elems.value.to(SortedMap).flatMap { case (lang, seq) => seq.map[ArrayEntry](toValueObject(_, Some(lang))) }
    val valuesNoLang = elems.others.map[ArrayEntry](toValueObject(_))
    expandArr(valuesNoLang ++ valuesWithLang)
  }

  private def withIndex(idx: String, elem: NodeObjectValue): NodeObjectValue =
    elem match {
      case WrappedNodeObject(v) => WrappedNodeObject(withIndex(idx, v))
      case v: ValueObject       => withIndex(idx, v)
      case v: LanguageMap       => withIndex(idx, v)
      case SetValue(v, _)       => SetValue(withIndex(idx, v))
      case ListValue(v, _)      => ListValue(withIndex(idx, v))
      case TypeMap(v, o)        => TypeMap(v.map { case (tpe, n) => tpe -> withIndex(idx, n) }, o.map(withIndex(idx, _)))
      case IdMap(v, o)          => IdMap(v.map { case (id, n) => id -> n.map(withIndex(idx, _)) }, o.map(withIndex(idx, _)))
      case v                    => v // do nothing
    }

  private def withIndex(idx: String, languageMap: LanguageMap): LanguageMap = {
    val v = languageMap.value.map { case (lang, seq) => lang -> seq.map { case (v, i) => v -> i.orElse(Some(idx)) } }
    val o = languageMap.others.map { case (v, i)     => v    -> i.orElse(Some(idx)) }
    LanguageMap(v, o)
  }

  private def withIndex(idx: String, node: NodeObject): NodeObject =
    node.copy(index = node.index.orElse(Some(idx)))

  private def withIndex(idx: String, value: ValueObject): ValueObject =
    value.copy(index = value.index.orElse(Some(idx)))

  private def withIndex(idx: String, seq: Seq[ArrayEntry]): Seq[ArrayEntry] =
    seq.map {
      case NodeObjectArray(value) => withIndex(idx, value)
      case NodeValueArray(value)  => withIndex(idx, value)
      case other                  => other
    }

  private def toJson(l: Literal): Json =
    (l.asBoolean.map(_.asJson) orElse
      l.asInt.map(_.asJson) orElse
      l.asLong.map(_.asJson) orElse
      l.asDouble.map(_.asJson)) getOrElse
      l.lexicalForm.asJson

  private def toValueObject(valueAndDirection: (String, Option[String]), lang: Option[LanguageTag] = None) =
    ValueObject(Literal(valueAndDirection._1, xsd.string, lang), direction = valueAndDirection._2)

  implicit val orderingLang: Ordering[LanguageTag] = (x: LanguageTag, y: LanguageTag) => x.value.compare(y.value)

}
