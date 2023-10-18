package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO
import fs2.Stream
import munit.{Assertions, Location}

import scala.concurrent.duration.DurationInt

trait CatsStreamAssertions { self: CatsRunContext with Assertions =>

  implicit class CEStreamAssertionsOps[A](stream: Stream[IO, A])(implicit
      loc: Location
  ) {
    def assert(expected: List[A]): IO[Unit] =
      stream.take(expected.size.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained, expected, s"Got ${obtained.size} elements, ${expected.size} elements were expected.")
      }

    def assertSize(expected: Int): IO[Unit] =
      stream.take(expected.toLong).timeout(3.seconds).mask.compile.toList.map { obtained =>
        assertEquals(obtained.size, expected, s"Got ${obtained.size} elements, $expected elements were expected.")
      }

    def assert(expected: A*): IO[Unit] = assert(expected.toList)

    def assertEmpty: IO[Unit] = assert(List.empty)
  }

}
