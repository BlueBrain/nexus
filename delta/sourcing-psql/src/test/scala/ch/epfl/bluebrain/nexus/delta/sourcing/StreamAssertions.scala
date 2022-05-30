package ch.epfl.bluebrain.nexus.delta.sourcing

import fs2.Stream
import monix.bio.Task
import munit.{Assertions, Location}

trait StreamAssertions { self: Assertions =>

  implicit class StreamAssertionsOps[A](stream: Stream[Task, A]) {
    def assert(expected: List[A])(implicit loc: Location): Task[Unit] = stream.compile.toList.map {
      assertEquals(_, expected)
    }
    def assert(expected: A*)(implicit loc: Location): Task[Unit]      = assert(expected.toList)
    def assertEmpty(implicit loc: Location): Task[Unit]               = assert(List.empty)
  }

}
