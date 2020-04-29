package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.Node
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary._
import ch.epfl.bluebrain.nexus.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.rdf.graph.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.ArrayEntry._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue._
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject._

private[jsonld] object ToGraph {

  final def apply(elem: NodeObject): Graph = Graph(elem.subject, node(elem, root = true))

  private def node(elem: NodeObject, root: Boolean = false): Set[Triple] =
    elem match {
      case NodeObject(_, Seq(), _, Seq(), graph, Seq(), _, _, Seq()) if root && graph.nonEmpty =>
        graph.toSet[NodeObject].foldLeft(Set.empty[Triple])(_ ++ node(_))
      case NodeObject(_, types, _, reverseSeq, _, includedSeq, _, _, termSeq) =>
        val typesTriples    = types.map[Triple](tpe => (elem.subject, rdf.tpe, tpe)).toSet
        val reverseTriples  = reverses(elem.subject, reverseSeq)
        val includedTriples = includedSeq.flatMap(node(_))
        val termTriples     = terms(elem.subject, termSeq)
        typesTriples ++ reverseTriples ++ termTriples ++ includedTriples
    }
//    elem match {
//      case NodeObject(_, Seq(), _, Seq(), graph, Seq(), _, None, Seq()) if root =>
//        if (graph.exists(_.nonEmptyValues)) expandNodes(elem.subject, graph) else Graph.empty
//      case NodeObject(uri, types, _, termsRev, graphSeq, includeSeq, _, idx, terms) =>
//        Graph(elem.subject, types.map[Triple](tpe => (elem.subject, rdf.tpe, tpe)).toSet)
//        val obj = JsonObject.fromMap(
//          VectorMap.empty[String, Json] ++
//            uri.map(id                                -> _.iriString.asJson) ++
//            Option.when(types.nonEmpty)(tpe           -> Json.fromValues(types.map(_.iriString.asJson))) ++
//            idx.map(index                             -> _.asJson) ++
//            Option.when(graphSeq.nonEmpty)(graph      -> expandNodes(graphSeq)) ++
//            Option.when(includeSeq.nonEmpty)(included -> expandNodes(includeSeq)) ++
//            Option.when(termsRev.nonEmpty)(reverse    -> expandReverse(termsRev)) ++
//            expandTerms(terms)
//        )
//        if (root) Json.arr(obj.asJson)
//        else obj.asJson
//
//    }

  private def reverses(s: IriOrBNode, reverseSeq: Seq[TermNodeObject]): Set[Triple] =
    reverseSeq.foldLeft(Set.empty[Triple]) {
      case (triples, (p, seq)) => triples ++ seq.toSet[NodeObject].flatMap(o => node(o) + ((o.subject, p, s)))
    }
  private def terms(s: IriOrBNode, termsSeq: Seq[TermValue]): Set[Triple] =
    // format: off
    termsSeq.foldLeft(Set.empty[Triple]) {
      case (triples, (p, WrappedNodeObject(o))) => triples + ((s, p, o.subject)) ++ node(o)
      case (triples, (p, o: ValueObject))       => triples + ((s, p, toLiteral(o)))
      case (triples, (p, langMap: LanguageMap)) => triples ++ toLiteral(langMap).map[Triple](o => (s, p, o))
      case (triples, (p, SetValue(seq, _)))     => triples ++ setValues(s, p, seq)
      case (triples, (p, ListValue(seq, _)))    => triples ++ listValue(s, p, seq)
      case (triples, (p, TypeMap(seq, oo)))     => triples ++ (seq.values ++ oo).flatMap(o => node(o) + ((s, p, o.subject))).toSet
      case (triples, (p, IdMap(seq, oo)))       => triples ++ (seq.values.flatten ++ oo).flatMap(o => node(o) + ((s, p, o.subject))).toSet
      case (triples, (p, IndexMap(seq, oo)))    => triples ++ terms(s, (seq.values.toSeq ++ oo).map(p -> _))
      case (triples, (p, JsonWrapper(o)))       => triples + ((s, p, Literal(o.noSpaces, rdf.json)))
    }
    // format: on

  private def setValues(s: IriOrBNode, p: Uri, seq: Seq[ArrayEntry]): Set[Triple] =
    seq.foldLeft(Set.empty[Triple]) {
      case (triples, NodeObjectArray(o))                   => triples + ((s, p, o.subject)) ++ node(o)
      case (triples, NodeValueArray(o))                    => triples + ((s, p, toLiteral(o)))
      case (triples, ListValueWrapper(ListValue(sseq, _))) => triples ++ listValue(s, p, sseq)
    }

  private def listValue(s: IriOrBNode, p: Uri, seq: Seq[ArrayEntry]): Set[Triple] = {

    def inner(triples: Set[Triple], sPrev: BNode, rest: Seq[ArrayEntry]): Set[Triple] =
      rest match {
        case NodeValueArray(o: ValueObject) +: Seq() =>
          inner(triples + ((sPrev, rdf.first, toLiteral(o))), sPrev, Seq())
        case NodeValueArray(o: ValueObject) +: _rest =>
          val b = Node.blank
          inner(triples + ((sPrev, rdf.first, toLiteral(o))) + ((sPrev, rdf.rest, b)), b, _rest)
        case NodeObjectArray(o) +: Seq() =>
          inner(triples ++ node(o) + ((sPrev, rdf.first, o.subject)), sPrev, Seq())
        case NodeObjectArray(o) +: _rest =>
          val b = Node.blank
          inner(triples ++ node(o) + ((sPrev, rdf.first, o.subject)) + ((sPrev, rdf.rest, b)), b, _rest)
        case ListValueWrapper(ListValue(o, _)) +: Seq() if o.isEmpty =>
          inner(triples + ((sPrev, rdf.first, rdf.nil)), sPrev, Seq())
        case ListValueWrapper(ListValue(o, _)) +: _rest if o.isEmpty =>
          val b = Node.blank
          inner(triples + ((sPrev, rdf.first, rdf.nil)) + ((sPrev, rdf.rest, b)), b, _rest)
        case ListValueWrapper(ListValue(o, _)) +: Seq() =>
          val b = Node.blank
          inner(triples + ((sPrev, rdf.first, b)), sPrev, Seq()) ++ inner(Set.empty, b, o)
        case ListValueWrapper(ListValue(o, _)) +: _rest =>
          val b = Node.blank
          val bb = Node.blank
          inner(triples + ((sPrev, rdf.first, b)) + ((sPrev, rdf.rest, bb)), bb, _rest) ++ inner(Set.empty, b, o)
        case Seq() => triples + ((sPrev, rdf.rest, rdf.nil))
      }

    if (seq.isEmpty)
      Set[Triple]((s, p, rdf.nil))
    else {
      val b = Node.blank
      inner(Set.empty, b, seq) + ((s, p, b))
    }
  }

  private def toLiteral(value: ValueObject): Literal =
    value.explicitType match {
      case Some(dataType) => Literal(value.literal.rdfLexicalForm, dataType, value.literal.languageTag)
      case None           => value.literal
    }

  private def toLiteral(languages: LanguageMap): Seq[Literal] =
    languages.value.toSeq.flatMap {
      case (lang, values)                     => values.map { case (str, _) => Literal(str, xsd.string, Some(lang)) }
    } ++ languages.others.map { case (str, _) => Literal(str) }

}
