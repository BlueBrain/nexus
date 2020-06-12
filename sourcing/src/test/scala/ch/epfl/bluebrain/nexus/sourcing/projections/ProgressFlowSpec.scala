package ch.epfl.bluebrain.nexus.sourcing.projections

import java.util.concurrent.ConcurrentHashMap

import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl._
import akka.stream.SourceShape
import cats.effect.{IO, Timer}
import ch.epfl.bluebrain.nexus.sourcing.projections.IndexingConfig.PersistProgressConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlow._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProgressFlowSpec._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.{NoProgress, OffsetProgress, OffsetsProgress}
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

class ProgressFlowSpec
    extends ActorSystemFixture("ProgressFlowSpec")
    with AnyWordSpecLike
    with Matchers
    with TestHelpers
    with BeforeAndAfter
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Eventually
    with BeforeAndAfterAll
    with ScalaFutures {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(15.second, 50.milliseconds)

  implicit val ec: ExecutionContextExecutor = system.dispatcher
  implicit private val projections          = mock[Projections[IO, String]]
  implicit private val timer: Timer[IO]     = IO.timer(system.dispatcher)
  implicit private val config               = PersistProgressConfig(2, 1.minute)

  before {
    Mockito.reset(projections)
  }

  sealed trait Ctx {
    val revs: Int                                    = 2
    val events: Int                                  = 5
    val allEvents: Long                              = (events * revs).toLong
    val finalOffset: Sequence                        = Sequence((events - 1).toLong)
    val id: String                                   = "progress-unique-id"
    val mainPId: String                              = "main"
    val f2PId: String                                = "es1"
    val f3PId: String                                = "es2"
    val failed: ConcurrentHashMap[String, Set[Long]] = new ConcurrentHashMap()

    val envelopes: List[EventEnvelope] = List
      .tabulate(events) { offset =>
        List.tabulate(revs) { rev =>
          val id = s"id-$offset"
          EventEnvelope(
            Sequence(offset.toLong),
            id,
            rev.toLong,
            Event(id, rev.toLong, offset.toLong),
            offset.toLong * 100L + rev.toLong
          )
        }
      }
      .flatten

    val source: Source[EventEnvelope, _]   = Source(envelopes).throttle(5, 1.second)
    val sourceMsg: Source[PairMsg[Any], _] = source.map[PairMsg[Any]](e => Right(Message(e, mainPId)))

    def index(pass: Resource => Boolean = _ => true): Resource => IO[Unit] =
      (res: Resource) =>
        if (pass(res))
          IO.unit
        else {
          val msg = s"resource '${res.persistenceId}', rev '${res.rev}' and offset '${res.offset}' not indexed"
          IO.raiseError(new RuntimeException(msg))
        }

    def toResource(pf: PartialFunction[Event, Resource]): Event => IO[Option[Resource]] =
      ev => IO.delay(pf.lift(ev))
  }

  "A source" should {

    "run and compute batch indexing from offset" in new Ctx {
      def index(list: List[Resource]): IO[Unit] = {
        list shouldEqual List(Resource("id-3", 1L, 3L), Resource("id-4", 1L, 4L))
        IO.unit
      }
      val offset: Offset                        = Sequence(1L)
      implicit val progress: ProjectionProgress = OffsetProgress(offset, 4L, 2L, 0L)

      val flow = ProgressFlowElem[IO, Any]
        .collectCast[Event]         // cast Any => Event
        .groupedWithin(6, 1.second) // batch: List[Event]
        .distinct()                 // remove duplicated persistenceIds: List[Event]
        .mapAsync(
          toResource { case ev if ev.offset != 2L => ev.toResource }
        )                           // convert to resource (None on 2): List[Option[Resource]]
        .collectSome[Resource] // Discard None (on 2): List[Resource]
        .runAsyncBatch(index)(Eval.After(offset)) //run index in batch
        .mergeEmit()
        .toProgress(progress)

      val resultProgress = sourceMsg.via(flow).runWith(Sink.last).futureValue
      resultProgress shouldEqual OffsetsProgress(Map(mainPId -> OffsetProgress(finalOffset, allEvents, 6L, 0L)))
    }

    "run and compute batch indexing from offset with failures" in new Ctx {
      def index(list: List[Resource]): IO[Unit] = {
        list shouldEqual List(Resource("id-3", 1L, 3L), Resource("id-4", 1L, 4L))
        IO.raiseError(new RuntimeException("Error on indexing"))
      }
      val offset: Offset                        = Sequence(1L)
      implicit val progress: ProjectionProgress = OffsetProgress(offset, 4L, 2L, 0L)

      val flow = ProgressFlowElem[IO, Any]
        .collectCast[Event]         // cast Any => Event
        .groupedWithin(6, 1.second) // batch: List[Event]
        .distinct()                 // remove duplicated persistenceIds: List[Event]
        .mapAsync(
          toResource { case ev if ev.offset != 2L => ev.toResource }
        )                           // convert to resource (None on 2): List[Option[Resource]]
        .collectSome[Resource] // Discard None (on 2): List[Resource]
        .runAsyncBatch(index)(Eval.After(offset)) //run index in batch
        .mergeEmit()
        .toPersistedProgress(id, progress)

      projections.recordFailure(mainPId, any[String], 1L, Sequence(3L), any[String]) shouldReturn IO.unit
      projections.recordFailure(mainPId, any[String], 1L, Sequence(4L), any[String]) shouldReturn IO.unit
      projections.recordProgress(id, any[OffsetsProgress]) shouldReturn IO.unit

      val resultProgress = sourceMsg.via(flow).runWith(Sink.last).futureValue
      resultProgress shouldEqual OffsetsProgress(Map(mainPId -> OffsetProgress(finalOffset, allEvents, 6L, 2L)))

      projections.recordFailure(mainPId, any[String], 1L, Sequence(3L), any[String]) wasCalled once
      projections.recordFailure(mainPId, any[String], 1L, Sequence(4L), any[String]) wasCalled once
      projections.recordProgress(id, any[OffsetsProgress]) wasCalled sixTimes
    }

    "run and compute progress starting from NoOffset" in new Ctx {
      implicit val progress: ProjectionProgress = NoProgress

      val graph: Source[ProjectionProgress, _] = Source.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val f1 = ProgressFlowElem[IO, Any]
          .collectCast[Event]         // cast Any => Event
          .groupedWithin(4, 1.second) // batch: List[Event]
          .distinct()                 // remove duplicated persistenceIds: List[Event]
          .mapAsync(
            toResource { case ev if ev.offset != 2L => ev.toResource }
          )                           // convert to resource (None on 2): List[Option[Resource]]
          .collectSome[Resource] // Discard None (on 2): List[Resource]
          .runAsync(index(_.offset != 3))() // indexing (fail on 3): List[Resource]
          .mergeEmit()
          .flow

        val f2: Flow[PairMsg[Resource], PairMsg[Resource], _] = ProgressFlowElem[IO, Resource]
          .select(f2PId) // Selects a certain progress
          .collect { case res if res.offset != 4L => res } // Discard on 4
          .mapAsync(res =>
            if (res.offset == 0L) IO.pure(None)
            else IO.pure(Option(res))
          )              // None on 0, Some otherwise: List[Option[Resource]]
          .collectSome[Resource] // Discard None (on 0): List[Resource]
          .runAsync(index(_.offset != 3))() // indexing (fail on 3): List[Resource]
          .flow

        val f3: Flow[PairMsg[Resource], PairMsg[Resource], _] = ProgressFlowElem[IO, Resource]
          .select(f3PId)                   // Selects a certain progress
          .runAsync(index(_.offset > 1))() // indexing (fail on 0 and 1): List[Resource]
          .flow

        val f4 = b.add(ProgressFlowList[IO, Resource].mergeCombine().toProgress(progress))

        val broadcast = b.add(Broadcast[PairMsg[Resource]](2))
        val merge     = b.add(ZipWithN[PairMsg[Resource], List[PairMsg[Resource]]](_.toList)(2))

        // format: off
        sourceMsg ~> f1 ~> broadcast
                           broadcast ~> f2 ~> merge
                           broadcast ~> f3 ~> merge
                                              merge ~> f4
        // format: on
        SourceShape(f4.out)
      })

      val resultProgress = graph.runWith(Sink.last).futureValue
      resultProgress shouldEqual
        OffsetsProgress(
          Map(
            mainPId -> OffsetProgress(finalOffset, allEvents, 5L + 1L, 1L),
            f2PId   -> OffsetProgress(finalOffset, allEvents, 5L + 1L + 1L + 2L, 0L),
            f3PId   -> OffsetProgress(finalOffset, allEvents, 5L + 1L + 1L, 2L)
          )
        )

    }

    "run and compute progress starting from offset and storing progress" in new Ctx {
      implicit val progress: ProjectionProgress = OffsetsProgress(
        Map(
          mainPId -> OffsetProgress(Sequence(3L), allEvents - 2, 4L + 1L, 1L),
          f2PId   -> OffsetProgress(Sequence(2L), allEvents - 4, 3L + 1L, 0L),
          f3PId   -> NoProgress
        )
      )

      val graph: Source[ProjectionProgress, _] = Source.fromGraph(GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._
        val f1 = ProgressFlowElem[IO, Any]
          .collectCast[Event]         // cast Any => Event
          .groupedWithin(4, 1.second) // batch: List[Event]
          .distinct()                 // remove duplicated persistenceIds: List[Event]
          .mapAsync(
            toResource { case ev if ev.offset != 2L => ev.toResource }
          )                           // convert to resource (None on 2): List[Option[Resource]]
          .collectSome[Resource] // Discard None (on 2): List[Resource]
          .runAsync(index(_.offset != 3))(Eval.After(Sequence(3L))) // indexing (fail on 3): List[Resource]
          .mergeEmit()
          .flow

        val f2: Flow[PairMsg[Resource], PairMsg[Resource], _] = ProgressFlowElem[IO, Resource]
          .select(f2PId)               // Selects a certain progress
          .evaluateAfter(Sequence(2L)) // Skip to anything after offset 2
          .collect { case res if res.offset != 4L => res } // Discard on 4
          .mapAsync(res =>
            if (res.offset == 0L) IO.pure(None)
            else IO.pure(Option(res))
          )                            // None on 0, Some otherwise: List[Option[Resource]]
          .collectSome[Resource] // Discard None (on 0): List[Resource]
          .runAsync(index(_.offset != 3))() // indexing (fail on 3): List[Resource]
          .flow

        val f3: Flow[PairMsg[Resource], PairMsg[Resource], _] = ProgressFlowElem[IO, Resource]
          .select(f3PId)                   // Selects a certain progress
          .evaluateAfter(NoOffset)         // Skip nothing
          .runAsync(index(_.offset > 1))() // indexing (fail on 0 and 1): List[Resource]
          .flow

        val f4 = b.add(ProgressFlowList[IO, Resource].mergeCombine().toPersistedProgress(id, progress))

        val broadcast = b.add(Broadcast[PairMsg[Resource]](2))
        val merge     = b.add(ZipWithN[PairMsg[Resource], List[PairMsg[Resource]]](_.toList)(2))

        // format: off
        sourceMsg ~> f1 ~> broadcast
                           broadcast ~> f2 ~> merge
                           broadcast ~> f3 ~> merge
                                              merge ~> f4
        // format: on
        SourceShape(f4.out)
      })

      projections.recordFailure(f3PId, any[String], 1L, Sequence(0L), any[String]) shouldReturn IO.unit
      projections.recordFailure(f3PId, any[String], 1L, Sequence(1L), any[String]) shouldReturn IO.unit
      projections.recordFailure(f2PId, any[String], 1L, Sequence(3L), any[String]) shouldReturn IO.unit
      projections.recordProgress(id, any[OffsetsProgress]) shouldReturn IO.unit

      val resultProgress = graph.runWith(Sink.last).futureValue
      resultProgress shouldEqual
        OffsetsProgress(
          Map(
            mainPId -> OffsetProgress(finalOffset, allEvents, 6L, 1L),
            f2PId   -> OffsetProgress(finalOffset, allEvents, 7L, 1L),
            f3PId   -> OffsetProgress(finalOffset, allEvents, 6L, 2L)
          )
        )

      projections.recordFailure(f3PId, any[String], 1L, Sequence(0L), any[String]) wasCalled once
      projections.recordFailure(f3PId, any[String], 1L, Sequence(1L), any[String]) wasCalled once
      projections.recordFailure(f2PId, any[String], 1L, Sequence(3L), any[String]) wasCalled once
      projections.recordProgress(id, any[OffsetsProgress]) wasCalled sixTimes
    }
  }

}

object ProgressFlowSpec {

  case class Resource(persistenceId: String, rev: Long, offset: Long)

  case class Event(persistenceId: String, rev: Long, offset: Long) {
    def toResource: Resource = Resource(persistenceId, rev, offset)
  }
}
