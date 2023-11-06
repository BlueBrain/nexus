package ch.epfl.bluebrain.nexus.testkit.mu

import munit.{Assertions, Location}

/**
  * MUnit assertions related to text
  */
trait TextAssertions { self: Assertions =>

  implicit class TextAssertionsOps(value: String)(implicit loc: Location) {

    def equalLinesUnordered(expected: String): Unit = {
      val valueSorted    = value.split("\n").filterNot(_.trim.isEmpty).sorted.toSeq
      val expectedSorted = expected.split("\n").filterNot(_.trim.isEmpty).sorted.toSeq
      assertEquals(
        valueSorted,
        expectedSorted,
        s"""
           |Both strings are different.
           |Diff:
           |${valueSorted.toList.diff(expectedSorted.toList).mkString("\n")}
           |Obtained:
           |${valueSorted.mkString("\n")}
           |Expected:
           |${expectedSorted.mkString("\n")}
           |""".stripMargin
      )
    }
  }

}
