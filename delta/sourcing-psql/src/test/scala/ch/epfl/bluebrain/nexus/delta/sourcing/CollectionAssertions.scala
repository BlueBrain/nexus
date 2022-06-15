package ch.epfl.bluebrain.nexus.delta.sourcing

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
  }

}
