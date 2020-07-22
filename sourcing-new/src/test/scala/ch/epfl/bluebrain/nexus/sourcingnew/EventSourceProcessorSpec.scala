package ch.epfl.bluebrain.nexus.sourcingnew

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.sourcingnew.Command.{Increment, IncrementAsync, Initialize}
import ch.epfl.bluebrain.nexus.sourcingnew.Event.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.sourcingnew.Rejection.InvalidRevision
import ch.epfl.bluebrain.nexus.sourcingnew.State.Current
import ch.epfl.bluebrain.nexus.sourcingnew.aggregate.EventSourceProcessor.{PersistentEventProcessor, TransientEventProcessor}
import ch.epfl.bluebrain.nexus.sourcingnew.aggregate.{DryRun, DryRunResult, Evaluate, EvaluateRejection, EvaluateResult, EvaluateSuccess, GetLastSeqNr, RequestLastSeqNr, RequestState}
import ch.epfl.bluebrain.nexus.sourcingnew.config.AggregateConfig
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

abstract class EventSourceProcessorSpec(config: Config)
    extends ScalaTestWithActorTestKit(config)
    with AnyWordSpecLike
    with Matchers {

  implicit val ctx: ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  implicit val timer: Timer[IO]      = IO.timer(ExecutionContext.global)

  val aggregateConfig = AggregateConfig(
    100.millis,
    100.millis,
    system.executionContext,
    100
  )

  val entityId      = "A"
  val persistenceId = "increment-A"

  def agg: ActorRef[aggregate.Command]

  "Evaluation" should {
    "update its state when accepting commands" in {
      expectEvaluate(
        Increment(0, 2)                 -> Right((Incremented(1, 2), Current(1, 2))),
        IncrementAsync(1, 5, 50.millis) -> Right((Incremented(2, 5), Current(2, 7)))
      )
    }

    "test without applying changes" in {
      expectDryRun(
        Current(2, 7),
        Initialize(0) -> Left(InvalidRevision(0)),
        Initialize(2) -> Right((Initialized(3), Current(3, 0)))
      )
    }

    "not update its state if evaluation fails" in {
      expectEvaluate(
        Initialize(0) -> Left(InvalidRevision(0))
      )

      val probeState = testKit.createTestProbe[State]()
      agg ! RequestState(entityId, probeState.ref)
      probeState.expectMessage(Current(2, 7))
    }

    "evaluate commands one at a time" in {
      expectEvaluate(
        Initialize(2)                   -> Right((Initialized(3), Current(3, 0))),
        IncrementAsync(3, 2, 70.millis) -> Right((Incremented(4, 2), Current(4, 2))),
        IncrementAsync(4, 2, 5.millis)  -> Right((Incremented(5, 2), Current(5, 4)))
      )
    }
  }

  protected def expectNothingPersisted(): Unit

  protected def expectNextPersisted(event: Event): Unit

  private def expectEvaluate(evaluate: (Command, Either[Rejection, (Event, State)])*) = {
    val probe      = testKit.createTestProbe[EvaluateResult]()
    val probeState = testKit.createTestProbe[State]()

    evaluate.foreach {
      case (command, result) =>
        agg ! Evaluate(entityId, command, probe.ref)

        result match {
          case Left(rejection)       =>
            expectNothingPersisted()
            probe.expectMessage(EvaluateRejection(rejection))
          case Right((event, state)) =>
            expectNextPersisted(event)
            probe.expectMessage(EvaluateSuccess(event, state))

            agg ! RequestState(entityId, probeState.ref)
            probeState.expectMessage(state)
        }

    }
  }

  private def expectDryRun(initialState: State, evaluate: (Command, Either[Rejection, (Event, State)])*) = {
    val probe      = testKit.createTestProbe[EvaluateResult]()
    val probeState = testKit.createTestProbe[State]()

    evaluate.foreach {
      case (command, result) =>
        agg ! DryRun(entityId, command, probe.ref)
        expectNothingPersisted()

        result match {
          case Left(rejection)       =>
            probe.expectMessage(DryRunResult(EvaluateRejection(rejection)))
          case Right((event, state)) =>
            probe.expectMessage(DryRunResult(EvaluateSuccess(event, state)))
        }

        agg ! RequestState(entityId, probeState.ref)
        probeState.expectMessage(initialState)

    }
  }
}

class PersistentEventProcessorSpec
    extends EventSourceProcessorSpec(
      PersistenceTestKitPlugin.config.withFallback(ConfigFactory.defaultApplication().resolve())
    )
    with BeforeAndAfterEach {

  override val agg = testKit.spawn(
    new PersistentEventProcessor[IO, State, Command, Event, Rejection](
      "increment",
      entityId,
      State.Initial,
      AggregateFixture.next,
      AggregateFixture.evaluate[IO],
      aggregateConfig
    ).behavior()
  )
  val persistenceTestKit = PersistenceTestKit(system)

  override def beforeEach(): Unit = {
    persistenceTestKit.clearAll()
  }

  override protected def expectNothingPersisted(): Unit =
    persistenceTestKit.expectNothingPersisted(persistenceId)

  override protected def expectNextPersisted(event: Event): Unit = {
    persistenceTestKit.expectNextPersisted(persistenceId, event)
    ()
  }

  "return its current seq nr" in {
    val probe = testKit.createTestProbe[GetLastSeqNr]()
    agg ! RequestLastSeqNr(entityId, probe.ref)

    probe.expectMessage(GetLastSeqNr(5L))
  }
}

class TransientEventProcessorSpec
    extends EventSourceProcessorSpec(
      PersistenceTestKitPlugin.config.withFallback(ConfigFactory.defaultApplication().resolve())
    )
    with BeforeAndAfterEach {

  override val agg: ActorRef[aggregate.Command] =
    testKit.spawn(
      new TransientEventProcessor[IO, State, Command, Event, Rejection](
        "increment",
        entityId,
        State.Initial,
        AggregateFixture.next,
        AggregateFixture.evaluate[IO],
        aggregateConfig
      ).behavior()
    )

  override protected def expectNothingPersisted(): Unit = {}

  override protected def expectNextPersisted(event: Event): Unit = {}
}
