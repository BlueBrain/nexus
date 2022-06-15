package ch.epfl.bluebrain.nexus.delta.sourcing

import fs2.Stream
import monix.bio.Task
import munit.{Assertions, Location}

trait StreamAssertions { self: Assertions =>

  implicit class StreamAssertionsOps[A](stream: Stream[Task, A])(implicit loc: Location) {
    def assert(expected: List[A]): Task[Unit] =
      stream.compile.toList.map {
        assertEquals(_, expected)
      }

    def assert(expected: A*): Task[Unit] = assert(expected.toList)
    def assertEmpty: Task[Unit]          = assert(List.empty)
  }

}
