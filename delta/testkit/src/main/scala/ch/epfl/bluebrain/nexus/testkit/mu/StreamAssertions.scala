package ch.epfl.bluebrain.nexus.testkit.mu

import cats.effect.IO
import fs2.Stream
import munit.{Assertions, Location}

import scala.concurrent.duration._

trait StreamAssertions extends Assertions {

  implicit class StreamAssertionsOps[A](stream: Stream[IO, A])(implicit loc: Location) {

    private def compileToList(take: Long) = stream.take(take).timeout(3.seconds).mask.compile.toList

    def assert(expected: List[A]): IO[Unit] =
      compileToList(expected.size.toLong).map { obtained =>
        assertEquals(obtained, expected, s"Got ${obtained.size} elements, ${expected.size} elements were expected.")
      }

    def assertSize(expected: Int): IO[Unit] =
      compileToList(expected.toLong).map { obtained =>
        assertEquals(obtained.size, expected, s"Got ${obtained.size} elements, $expected elements were expected.")
      }

    def assert(expected: A*): IO[Unit] = assert(expected.toList)

    def assertEmpty: IO[Unit] = assertSize(0)

    def assertAll(take: Long, predicate: A => Boolean): IO[Unit] =
      compileToList(take).map { obtained =>
        Assertions.assert(obtained.forall(predicate), "All the elements don't match the predicate.")
      }
  }

}
