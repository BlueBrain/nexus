package ch.epfl.bluebrain.nexus.delta.sourcing

import fs2.Stream
import monix.bio.Task
import munit.Assertions

trait StreamAssertions { self: Assertions =>

  def assertStream[A](stream: Stream[Task, A], expected: List[A]): Task[Unit] =
    stream.compile.toList.map {
      assertEquals(_, expected)
    }

  def assertEmptyStream[A](stream: Stream[Task, A]): Task[Unit] =
    assertStream(stream, List.empty)

}
