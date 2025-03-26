package ch.epfl.bluebrain.nexus.testkit.mu

import munit.{Assertions, Location}

trait StringAssertions { self: Assertions =>

  implicit class StringAssertionsOps(obtained: String)(implicit loc: Location) {

    private def sort(value: String) = value.split("\n").filterNot(_.trim.isEmpty).sorted.toList

    def equalLinesUnordered(expected: String)(implicit loc: Location): Unit = {
      val obtainedSorted = sort(obtained)
      val expectedSorted = sort(expected)

      assertEquals(
        obtainedSorted,
        expectedSorted,
        s"""
           |Both strings are different.
           |Diff:
           |${obtainedSorted.diff(expectedSorted).mkString("\n")}
           |Obtained:
           |${obtainedSorted.mkString("\n")}
           |Expected:
           |${expectedSorted.mkString("\n")}
           |""".stripMargin
      )

    }
  }

}
