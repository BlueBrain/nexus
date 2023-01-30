package ch.epfl.bluebrain.nexus.testkit.bio

import fs2.Stream
import monix.bio.Task
import munit.{Assertions, Location}

import scala.concurrent.duration.DurationInt

trait StreamAssertions { self: Assertions =>

  implicit class StreamAssertionsOps[A](stream: Stream[Task, A])(implicit loc: Location) {
    def assert(expected: List[A]): Task[Unit] =
      stream.take(expected.size.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained, expected, s"Got ${obtained.size} elements, ${expected.size} elements were expected.")
      }

    def assertSize(expected: Int): Task[Unit] =
      stream.take(expected.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained.size, expected, s"Got ${obtained.size} elements, $expected elements were expected.")
      }

    def assert(expected: A*): Task[Unit] = assert(expected.toList)
    def assertEmpty: Task[Unit]          = assert(List.empty)
  }

}
