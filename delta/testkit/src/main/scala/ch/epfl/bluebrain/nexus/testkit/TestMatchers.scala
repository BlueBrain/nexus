package ch.epfl.bluebrain.nexus.testkit

import org.scalatest.matchers.{MatchResult, Matcher}

trait TestMatchers {

  def equalLinesUnordered(right: String): Matcher[String] = new EqualLinesUnordered(right)

  private class EqualLinesUnordered(right: String) extends Matcher[String] {

    override def apply(left: String): MatchResult = {
      val leftSorted  = left.split("\n").filterNot(_.trim.isEmpty).sorted
      val rightSorted = right.split("\n").filterNot(_.trim.isEmpty).sorted
      MatchResult(
        leftSorted sameElements rightSorted,
        s"""
           |Both strings are different.
           |Diff:
           |${rightSorted.toList.diff(leftSorted.toList).mkString("\n")}
           |Left:
           |${leftSorted.mkString("\n")}
           |Right:
           |${rightSorted.mkString("\n")}
           |""".stripMargin,
        ""
      )
    }
  }
}
