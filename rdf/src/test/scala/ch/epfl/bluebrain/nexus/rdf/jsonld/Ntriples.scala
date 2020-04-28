package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.jsonld.Ntriples._
import org.scalactic.Equality

import scala.annotation.tailrec
import scala.util.Try

private class BNodeReplacement private[jsonld] (a: Seq[TripleS], b: Seq[TripleS]) {

  def apply: Set[TripleS] = inner(0, b, Map.empty)

  @tailrec
  private def inner(
      pos: Int,
      replaced: Seq[TripleS],
      replacements: Map[String, String]
  ): Set[TripleS] =
    a.drop(pos).zip(replaced.drop(pos)) match {
      case ((_, p, _), (_, pp, _)) +: _ if p != pp =>
        replaced.toSet
      case ((s, _, o), (ss, _, oo)) +: _ if s == ss && o == oo =>
        inner(pos + 1, replaced, replacements)
      case ((s, _, o), (ss, _, oo)) +: _ if isBNode(s) != isBNode(ss) || isBNode(o) != isBNode(oo) =>
        replaced.toSet
      case ((s, _, o), (ss, _, oo)) +: _ =>
        val sss =
          replacements.get(ss).fold(Option.when(!replacements.values.exists(_ == ss))(s))(v => Option.when(v == s)(s))
        val ooo =
          replacements.get(oo).fold(Option.when(!replacements.values.exists(_ == oo))(o))(v => Option.when(v == o)(o))
        if ((isBNode(s) && isBNode(ss) && sss.nonEmpty && isBNode(o) && isBNode(oo) && ooo.nonEmpty) ||
            (isBNode(o) && isBNode(oo) && ooo.nonEmpty & s == ss) ||
            (isBNode(s) && isBNode(ss) && sss.nonEmpty && o == oo)) {
          val newReplacedS  = replaceAll(ss, s, replaced)
          val newReplacedSO = replaceAll(oo, o, newReplacedS)
          inner(pos + 1, newReplacedSO, replacements + (ss -> s) + (oo -> o))
        } else
          replaced.toSet

      case Seq() => replaced.toSet
    }

  private def replaceAll(a: String, b: String, target: Seq[TripleS]): Seq[TripleS] =
    target.map { case (s, p, o) => (if (s == a) b else s, p, if (o == a) b else o) }

}

final case class Ntriples(value: String)

object Ntriples {

  implicit val ntriplesEq: Equality[Ntriples] = new Equality[Ntriples] {
    override def areEqual(a: Ntriples, b: Any): Boolean =
      Try(b.asInstanceOf[Ntriples]).map(Ntriples.areEqual(a, _)).getOrElse(false)
  }

  private[jsonld] type TripleS = (String, String, String)

  def areEqual(a: Ntriples, b: Ntriples): Boolean =
    areEqual(toTuples(b.value), toTuples(a.value))

  private def areEqual(a: Set[TripleS], b: Set[TripleS]): Boolean =
    if (a.size != b.size) false
    else if (a.isEmpty) true
    else if (a.size == 1) new BNodeReplacement(a.toList, b.toList).apply == a
    else {
      val orderedA = a.toList.sorted(lexicalOrder)
      val orderedB = b.toList.sorted(lexicalOrder)
      val orderedBCandidates =
        orderedB.zip(orderedB.tail).zipWithIndex.foldLeft(Set(orderedB)) {
          case (candidates, ((cur, next), idx)) if compareToIgnoringBNode(cur, next) == 0 && cur != next =>
            candidates ++ candidates.map(_.updated(idx, next).updated(idx + 1, cur))
          case (candidates, _) => candidates
        }
      orderedBCandidates.exists(candidateB => new BNodeReplacement(orderedA, candidateB).apply == a)
    }

  private def toTuples(s: String): Set[TripleS] =
    if (s.isBlank)
      Set.empty[TripleS]
    else
      s.split("\n")
        .toSet[String]
        .map(line =>
          line.split(" ", 3) match {
            case Array(s, p, o) => (s, p, o.dropRight(2))
          }
        )

  private[jsonld] def isBNode(s: String) = s.startsWith("_:")

  private val lexicalOrder: Ordering[TripleS] = compareToIgnoringBNode

  private def compareToIgnoringBNode(a: TripleS, b: TripleS): Int =
    (a, b) match {
      case ((s, p, o), (ss, pp, oo)) =>
        val finalS  = if (isBNode(s)) "" else s
        val finalSS = if (isBNode(ss)) "" else ss
        val finalO  = if (isBNode(o)) "" else o
        val finalOO = if (isBNode(oo)) "" else oo
        s"$finalS$p$finalO".compare(s"$finalSS$pp$finalOO")
    }

}
