package ch.epfl.bluebrain.nexus.delta.sourcing

import fs2.Stream
import monix.bio.Task
import munit.{Assertions, Location}

trait StreamAssertions { self: Assertions =>

  def assertStream[A](stream: Stream[Task, A], expected: List[A])(implicit loc: Location): Task[Unit] =
    stream.compile.toList.map {
      assertEquals(_, expected)
    }

  def assertStream[A](stream: Stream[Task, A], expected: A*)(implicit loc: Location): Task[Unit] =
    assertStream(stream, expected.toList)

  def assertEmptyStream[A](stream: Stream[Task, A])(implicit loc: Location): Task[Unit] =
    assertStream(stream, List.empty)

  implicit class StreamAssertionsOps[A](stream: Stream[Task, A]) {
    def assert(expected: List[A])(implicit loc: Location): Task[Unit] = assertStream(stream, expected)
    def assert(expected: A*)(implicit loc: Location): Task[Unit]      = assertStream(stream, expected)
    def assertEmpty(implicit loc: Location): Task[Unit]               = assertEmptyStream(stream)
  }

}
