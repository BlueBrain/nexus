package ch.epfl.bluebrain.nexus.testkit.bio

import cats.effect.{Concurrent, Timer}
import cats.syntax.all._
import fs2.Stream
import munit.{Assertions, Location}

import scala.concurrent.duration.DurationInt

trait StreamAssertions { self: Assertions =>

  implicit class StreamAssertionsOps[F[_], A](stream: Stream[F, A])(implicit
      loc: Location,
      t: Timer[F],
      c: Concurrent[F]
  ) {
    def assert(expected: List[A]): F[Unit] =
      stream.take(expected.size.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained, expected, s"Got ${obtained.size} elements, ${expected.size} elements were expected.")
      }

    def assertSize(expected: Int): F[Unit] =
      stream.take(expected.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained.size, expected, s"Got ${obtained.size} elements, $expected elements were expected.")
      }

    def assert(expected: A*): F[Unit] = assert(expected.toList)
    def assertEmpty: F[Unit]          = assert(List.empty)
  }

}
