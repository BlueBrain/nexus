package ch.epfl.bluebrain.nexus.delta.sourcing2

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sourcing2.EntityDefinition.PersistentDefinition.StopStrategy
import ch.epfl.bluebrain.nexus.delta.sourcing2.ProcessorCommand.Request
import ch.epfl.bluebrain.nexus.delta.sourcing2.config.{AggregateConfig, TrackQueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventStore
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityId
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateStore
import ch.epfl.bluebrain.nexus.delta.sourcing2.track.{TrackConfig, TrackStore}
import ch.epfl.bluebrain.nexus.testkit.IOFixedClock
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class EventSourcedBehaviourSpec
  extends ScalaTestWithActorTestKit(ConfigFactory.load())
  with AnyWordSpecLike
  with PostgresSetup
  with IOFixedClock
  with Matchers {

  private val shared           = Transactors.shared(xa)
  private val trackStore       = TrackStore(TrackConfig(500L, 30.minutes), shared)
  private val trackQueryConfig = TrackQueryConfig(2, 50.millis)
  private val deltaVersion = "1.8"

  private val eventStore = EventStore(trackStore, trackQueryConfig, shared)
  private val stateStore = StateStore(trackStore, trackQueryConfig, shared)
  private val entityStore = EntityStore(eventStore, stateStore, trackStore, shared, deltaVersion)

  private val aggregateConfig = AggregateConfig(5.seconds, 5.seconds, 100, StopStrategy.never, RetryStrategyConfig.AlwaysGiveUp)

  private val eventSourceBehaviour = new EventSourcedBehaviour(entityStore)

  private val stopProbe = testKit.createTestProbe[String]()

  def stop(actorRef: ActorRef[ProcessorCommand]): Behavior[ProcessorCommand] = {
    stopProbe.ref ! s"${actorRef.path.name} got stopped"
    Behaviors.stopped[ProcessorCommand]
  }

  val sender = testKit.createTestProbe[Response]()

  val entityId = EntityId.unsafe("entity")

  val aggregate = testKit.spawn(
    eventSourceBehaviour(
      EntityId.unsafe("entity"),
      TestEntity.persistentDefinition,
      TestEntity.serializer,
      aggregateConfig,
      stop
    )
  )

  "Getting initial state" should {
    "return None" in {
      aggregate ! Request.GetState(entityId, sender.ref)

      sender.expectMessage(Response.StateResponse(None))
    }
  }

  "Evaluating" should {

    "update its state when accepting commands" in {

    }

    "test without applying and persisting changes" in {

    }

    "not update the state if evaluation fails" in {

    }

  }




}
