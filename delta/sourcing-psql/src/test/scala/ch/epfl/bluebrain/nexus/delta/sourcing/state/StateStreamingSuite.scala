package ch.epfl.bluebrain.nexus.delta.sourcing.state

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState
import ch.epfl.bluebrain.nexus.delta.sourcing.PullRequest.PullRequestState.PullRequestActive
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Envelope, Label, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.RefreshStrategy
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.Doobie
import doobie.implicits._
import fs2.concurrent.Queue
import monix.bio.Task
import munit.AnyFixture

import java.time.Instant
import scala.concurrent.duration.DurationInt

class StateStreamingSuite extends BioSuite with Doobie.Fixture with Doobie.Assertions {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobie)

  private lazy val xas = doobie()

  private val queryConfig = QueryConfig(10, RefreshStrategy.Stop)

  private lazy val prStore = ScopedStateStore[Label, PullRequestState](
    PullRequest.entityType,
    PullRequestState.serializer,
    queryConfig,
    xas
  )

  private val alice = User("Alice", Label.unsafe("Wonderland"))

  private val project1 = ProjectRef.unsafe("org", "proj1")
  private val project2 = ProjectRef.unsafe("org", "proj2")
  private val project3 = ProjectRef.unsafe("org2", "proj2")

  private val id1 = Label.unsafe("1")
  private val id2 = Label.unsafe("2")
  private val id4 = Label.unsafe("4")

  private val customTag = UserTag.unsafe("v0.1")

  private val state1        = PullRequestActive(id1, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state2        = PullRequestActive(id2, project1, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val state3 = PullRequestActive(id1, project2, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)
  private val state4 = PullRequestActive(id4, project3, 1, Instant.EPOCH, Anonymous, Instant.EPOCH, alice)

  private val envelope1       = Envelope(PullRequest.entityType, id1, 1, state1, Instant.EPOCH, Offset.at(1L))
  private val envelope2       = Envelope(PullRequest.entityType, id2, 1, state2, Instant.EPOCH, Offset.at(2L))
  private val envelope1Tagged = Envelope(PullRequest.entityType, id1, 1, state1, Instant.EPOCH, Offset.at(5L))

  private def states(project: ProjectRef, tag: Tag, offset: Offset): Task[List[Envelope[String, PullRequestState]]] =
    for {
      queue <- Queue.unbounded[Task, Envelope[String, PullRequestState]]
      _     <- StateStreaming
                 .scopedStates(
                   project,
                   tag,
                   offset,
                   queryConfig,
                   xas,
                   (_, json) => Task.pure(PullRequestState.serializer.codec.decodeJson(json).toOption)
                 )
                 .through(queue.enqueue)
                 .compile
                 .drain
                 .timeout(500.millis)
      elems <- queue.tryDequeueChunk1(Int.MaxValue).map(opt => opt.map(_.toList).getOrElse(Nil))
    } yield elems

  test("Save existing states") {
    for {
      _ <- List(state1, state2, state3, state4).traverse(prStore.save).transact(xas.write)
      _ <- List(state1, state3).traverse(prStore.save(_, customTag)).transact(xas.write)
    } yield ()
  }

  test("Fetch all current latest states from the beginning") {
    for {
      elems <- states(project1, Tag.Latest, Offset.Start)
      _      = assertEquals(elems, List(envelope1, envelope2).map(e => e.copy(id = e.id.value)))
    } yield ()
  }

  test("Fetch all current latest states from offset 1") {
    for {
      elems <- states(project1, Tag.Latest, Offset.at(1L))
      _      = assertEquals(elems, List(envelope2).map(e => e.copy(id = e.id.value)))
    } yield ()
  }

  test("Fetch tagged states from the beginning") {
    for {
      elems <- states(project1, customTag, Offset.Start)
      _      = assertEquals(elems, List(envelope1Tagged).map(e => e.copy(id = e.id.value)))
    } yield ()
  }
}
