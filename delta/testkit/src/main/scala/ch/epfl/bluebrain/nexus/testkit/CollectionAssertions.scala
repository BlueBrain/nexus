package ch.epfl.bluebrain.nexus.testkit

import munit.{Assertions, Location}

trait CollectionAssertions { self: Assertions =>

  implicit class CollectionAssertionsOps[A](cc: Iterable[A])(implicit loc: Location) {

    def assertContains(value: A): Unit =
      assert(cc.exists(_ == value), s"Element $value not found in the collection")

    def assertContainsAllOf(elements: Set[A]): Unit = {
      val found    = cc.foldLeft(Set.empty[A]) {
        case (acc, e) if elements.contains(e) => acc + e
        case (acc, _)                         => acc
      }
      val notfound = elements.diff(found)
      assert(notfound.isEmpty, s"Element(s) ${notfound.mkString(", ")} were not found in the collection")
    }

    def assertEmpty(): Unit =
      assert(cc.isEmpty, s"The collection is not empty, it has ${cc.size} elements.")

    def assertSize(expected: Int): Unit =
      assertEquals(cc.size, expected, s"The collection does not have the expected size of $expected, but ${cc.size}")

    def assertOneElem: A = {
      assertSize(1)
      cc.headOption match {
        case Some(value) => value
        case None        => fail("Could not collect the single element of the collection")
      }
    }
  }

}
