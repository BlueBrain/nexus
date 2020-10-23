package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.Sequence
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.config.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionId.ViewProjectionId
import fs2.{Chunk, Stream}
import io.circe.Json
import monix.bio.Task
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

final case class DummyException(message: String) extends Exception(message)

class StreamOpsSpec extends AnyWordSpecLike with Matchers {

  import StreamOpsSpec._
  import monix.execution.Scheduler.Implicits.global

  implicit val projectionId: ViewProjectionId = ViewProjectionId("myProjection")

  /**
    * Generate a stream of messages with numberOfEvents persistenceId
    * each with numberOfRevisions
    *
    * @return
    */
  def streamFrom(
      numberOfEvents: Int,
      numberOfRevisions: Int,
      discardedIndex: Set[(Int, Int)],
      errorIndex: Set[(Int, Int)]
  ): Stream[Task, Message[String]] =
    Stream
      .emits(
        List
          .tabulate(numberOfEvents) { event =>
            List.tabulate(numberOfRevisions) { revision =>
              val offset = event * numberOfRevisions + revision
              val value  = s"$event-$revision"
              (discardedIndex.contains(event -> revision), errorIndex.contains(event -> revision)) match {
                case (true, true)   => throw new IllegalArgumentException("It can't be both")
                case (true, false)  =>
                  (offset, event, revision).discarded
                case (false, true)  =>
                  (offset, event, revision, value).failed
                case (false, false) =>
                  (offset, event, revision, value).success
              }
            }
          }
          .flatten
      )
      .covary[Task]

  import ProjectionStream._
  "Fetching resources" should {
    val stream = streamFrom(2, 2, Set(1 -> 0), Set(1 -> 1))

    def fetch(input: String): Task[Option[Json]] = {
      input match {
        case "0-1" => Task.raiseError(DummyException("0-1"))
        case s     =>
          Task.pure(
            Some(Json.fromString(s.prependedAll("fetched-").toUpperCase))
          )
      }
    }

    def filterMap(input: Json) = Some(Json.obj("value" -> input))

    // Expected result after applying fetch
    val expected = List(
      (0, 0, 0, Json.fromString("FETCHED-0-0")).success,
      (1, 0, 1, "0-1").failed,
      (2, 1, 0).discarded,
      (3, 1, 1, "1-1").failed
    )

    // Expected result after applying fetch + filterMap
    val expectedWithFilter =
      (0, 0, 0, Json.obj("value" -> Json.fromString("FETCHED-0-0"))).success :: expected.tail

    "work with a plain stream" in {
      val messages: List[Message[Json]] = stream.resourceIdentity(fetch).compile.toList.runSyncUnsafe()

      messages should contain theSameElementsInOrderAs expected

      val messages2: List[Message[Json]] = stream.resource(fetch, filterMap).compile.toList.runSyncUnsafe()
      messages2 should contain theSameElementsInOrderAs expectedWithFilter
    }

    "work with a grouped stream" in {
      val messages: List[Chunk[Message[Json]]] =
        stream.groupWithin(2, 5.seconds).resourceIdentity(fetch).compile.toList.runSyncUnsafe()
      messages should contain theSameElementsInOrderAs expected.grouped(2).map(Chunk.seq(_)).toList

      val messages2: List[Chunk[Message[Json]]] =
        stream.groupWithin(2, 5.seconds).resource(fetch, filterMap).compile.toList.runSyncUnsafe()
      messages2 should contain theSameElementsInOrderAs expectedWithFilter.grouped(2).map(Chunk.seq(_)).toList
    }
  }

  "Deduplicating" should {

    // Stream to deduplicate
    val stream = Stream
      .emits(
        (0, 0, 0, "first").success ::
          (1, 0, 1, "failed").failed ::
          (2, 1, 0, "second").success ::
          (3, 0, 2).discarded ::
          (4, 1, 1, "second new").success ::
          (5, 1, 2).discarded ::
          (6, 2, 0, "third kept").success ::
          Nil
      )
      .covary[Task]

    "mark duplicate messages as discarded as they are in the same chunk" in {
      val messages = stream
        .groupWithin(7, 2.seconds)
        .discardDuplicatesAndFlatten()
        .compile
        .toList
        .runSyncUnsafe()

      val expected =
        // Only one chunk
        (0, 0, 0, "first").success ::
          (1, 0, 1, "failed").failed ::
          (2, 1, 0).discarded :: // <- Duplicate
          (3, 0, 2).discarded ::
          (4, 1, 1, "second new").success ::
          (5, 1, 2).discarded ::
          (6, 2, 0, "third kept").success ::
          Nil

      messages should contain theSameElementsInOrderAs expected
    }

    "not mark duplicate any message as discarded if they are in different chunks" in {
      val messages = stream
        .groupWithin(3, 2.seconds)
        .discardDuplicatesAndFlatten()
        .compile
        .toList
        .runSyncUnsafe()

      val expected =
        // First chunk
        (0, 0, 0, "first").success ::
          (1, 0, 1, "failed").failed ::
          (2, 1, 0, "second").success ::
          // Second chunk
          (3, 0, 2).discarded ::
          (4, 1, 1, "second new").success ::
          (5, 1, 2).discarded ::
          // Third chunk
          (6, 2, 0, "third kept").success ::
          Nil

      messages should contain theSameElementsInOrderAs expected
    }
  }

  "Discard on replay" should {

    // Stream to evaluate
    val stream = Stream
      .emits(
        (0, 0, 0, "first").success ::
          (1, 0, 1).discarded ::
          (2, 1, 0, "second").success ::
          (3, 0, 2, "failed").failed ::
          (4, 1, 1, "second new").success ::
          (5, 2, 0, "third").success ::
          Nil
      )
      .covary[Task]

    "discard all messages with a smaller offset" in {
      val expected =
        (0, 0, 0).discarded ::   // <- Discarded
          (1, 0, 1).discarded ::
          (2, 1, 0).discarded :: // <- Discarded
          (3, 0, 2, "failed").failed ::
          (4, 1, 1, "second new").success ::
          (5, 2, 0, "third").success ::
          Nil

      val messages = stream.discardOnReplay(Sequence(2L)).compile.toList.runSyncUnsafe()
      messages should contain theSameElementsInOrderAs expected
    }
  }

  "Running async" should {

    // Stream to evaluate
    val stream = Stream
      .emits(
        (0, 0, 0, "first").success ::
          (1, 0, 1).discarded ::
          (2, 1, 0, "second").success ::
          (3, 0, 2, "failed").failed ::
          (4, 1, 1, "second new").success ::
          (5, 2, 0, "third").success ::
          Nil
      )
      .covary[Task]

    def f(input: String) =
      input match {
        case "second" => Task.raiseError(DummyException("Run exception !"))
        case _        => Task.unit
      }

    "Mark individual failures for a plain stream" in {
      val messages = stream.runAsync(f).compile.toList.runSyncUnsafe()

      val expected =
        (0, 0, 0, "first").success ::
          (1, 0, 1).discarded ::
          (2, 1, 0, "second", "Run exception !").failed :: // <- Failed
          (3, 0, 2, "failed").failed ::
          (4, 1, 1, "second new").success ::
          (5, 2, 0, "third").success ::
          Nil

      messages should contain theSameElementsInOrderAs expected
    }

    "Not marked the message as failed as it has been excluded due to the predicate" in {
      val messages = stream.runAsync(f, Message.filterOffset(Sequence(2L))).compile.toList.runSyncUnsafe()

      val expected =
        (0, 0, 0, "first").success ::
          (1, 0, 1).discarded ::
          (2, 1, 0, "second").success :: // <- f has not been applied and therefore hasn't failed
          (3, 0, 2, "failed").failed ::
          (4, 1, 1, "second new").success ::
          (5, 2, 0, "third").success ::
          Nil

      messages should contain theSameElementsInOrderAs expected
    }

    "Mark all batch as failed for a grouped stream" in {
      val messages = stream
        .groupWithin(3, 2.seconds)
        .runAsync { list =>
          list.map(f).sequence >> Task.unit
        }
        .compile
        .toList
        .runSyncUnsafe()

      val expected =
        Chunk.seq(
          (0, 0, 0, "first", "Run exception !").failed ::    // Failed because of the third item
            (1, 0, 1).discarded ::                           // Untouched
            (2, 1, 0, "second", "Run exception !").failed :: // <- Failed
            Nil
        ) ::
          Chunk.seq(
            (3, 0, 2, "failed").failed ::
              (4, 1, 1, "second new").success ::
              (5, 2, 0, "third").success ::
              Nil
          ) :: Nil

      messages should contain theSameElementsInOrderAs expected
    }

    "Not marked the batch as failed as the third message has been excluded due to the predicate" in {
      val messages = stream
        .groupWithin(3, 2.seconds)
        .runAsync(
          { list =>
            list.map(f).sequence >> Task.unit
          },
          Message.filterOffset(Sequence(2L))
        )
        .compile
        .toList
        .runSyncUnsafe()

      val expected =
        Chunk.seq(
          (0, 0, 0, "first").success ::
            (1, 0, 1).discarded ::
            (2, 1, 0, "second").success :: // <- f has not been applied and therefore hasn't failed
            Nil
        ) ::
          Chunk.seq(
            (3, 0, 2, "failed").failed ::
              (4, 1, 1, "second new").success ::
              (5, 2, 0, "third").success ::
              Nil
          ) :: Nil

      messages should contain theSameElementsInOrderAs expected
    }
  }

  "Persisting progress" should {
    "reporting correctly progress and errors during the given projection" in {
      // Results to be asserted later
      var errorCalls     = 0
      var resultProgress = ProjectionProgress.NoProgress

      val stream = Stream
        .emits(
          (1, 0, 0, "first").success ::
            (2, 0, 1).discarded ::
            (3, 1, 0, "second").success ::
            (4, 0, 2, "failed").failed ::
            (5, 1, 1, "second new").success ::
            (6, 2, 0, "third").failed ::
            Nil
        )
        .covary[Task]

      stream
        .persistProgress(
          resultProgress,
          // Stub method to retrieve the accumulated progress
          (_: ProjectionId, projectionProgress: ProjectionProgress) => {
            Task.pure { resultProgress = projectionProgress } >>
              Task.unit
          },
          // Stub method to trace error persist calls
          (_, _) => Task.pure { errorCalls += 1 } >> Task.unit,
          PersistProgressConfig(3, 5.seconds)
        )
        .compile
        .drain
        .runSyncUnsafe()

      errorCalls shouldBe 2
      resultProgress shouldBe ProjectionProgress(
        offset = Sequence(6L),
        processed = 6L,
        discarded = 1L,
        failed = 2L
      )
    }
  }
}

object StreamOpsSpec {

  implicit class IntTupleOps3(tuple: (Int, Int, Int)) {
    def discarded: DiscardedMessage =
      DiscardedMessage(
        Sequence(tuple._1.toLong),
        s"persistence-${tuple._2}",
        tuple._3.toLong
      )
  }

  implicit class IntTupleOps4[A](tuple: (Int, Int, Int, A)) {

    def success: SuccessMessage[A] =
      SuccessMessage[A](Sequence(tuple._1.toLong), s"persistence-${tuple._2}", tuple._3.toLong, tuple._4)

    def failed: FailureMessage[A] =
      FailureMessage(
        Sequence(tuple._1.toLong),
        s"persistence-${tuple._2}",
        tuple._3.toLong,
        tuple._4,
        DummyException(s"${tuple._2}-${tuple._3}")
      )
  }

  implicit class IntTupleOps5[A](tuple: (Int, Int, Int, A, String)) {

    def success: SuccessMessage[A] =
      SuccessMessage[A](Sequence(tuple._1.toLong), s"persistence-${tuple._2}", tuple._3.toLong, tuple._4)

    def failed: FailureMessage[A] =
      FailureMessage(
        Sequence(tuple._1.toLong),
        s"persistence-${tuple._2}",
        tuple._3.toLong,
        tuple._4,
        DummyException(tuple._5)
      )
  }
}
