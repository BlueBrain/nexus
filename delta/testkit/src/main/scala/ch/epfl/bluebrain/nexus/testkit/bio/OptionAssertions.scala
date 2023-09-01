package ch.epfl.bluebrain.nexus.testkit.bio

import munit.{Assertions, Location}

trait OptionAssertions { self: Assertions =>

  implicit class OptionAssertionsOps[A](option: Option[A])(implicit loc: Location) {

    def assertSome(value: A): Unit =
      assert(option.contains(value), s"$value was not found in the option, it contains '${option.getOrElse("None")}'")
    def assertNone(): Unit         =
      assert(
        option.isEmpty,
        s"The option is not empty, it contains ${option.getOrElse(new IllegalStateException("Should not happen))}."))}"
      )

    def assertSome(): Unit =
      assert(option.isDefined, s"The option is empty.")
  }

}
