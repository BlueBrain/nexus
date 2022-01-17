package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.EntityDefinition.PersistentDefinition
import ch.epfl.bluebrain.nexus.delta.sourcing2.TestCommand._
import ch.epfl.bluebrain.nexus.delta.sourcing2.TestEvent.{Incremented, Initialized}
import ch.epfl.bluebrain.nexus.delta.sourcing2.TestRejection.InvalidRevision
import ch.epfl.bluebrain.nexus.delta.sourcing2.TestState.Current
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder.SingleDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateEncoder
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import monix.bio.IO

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

sealed trait TestCommand extends Product with Serializable {
  def rev: Int
}

object TestCommand {
  case class Increment(rev: Int, step: Int)                             extends TestCommand
  case class IncrementAsync(rev: Int, step: Int, sleep: FiniteDuration) extends TestCommand
  case class Initialize(rev: Int)                                       extends TestCommand
  case class Boom(rev: Int, message: String)                            extends TestCommand
  case class Never(rev: Int)                                            extends TestCommand
}

sealed trait TestEvent extends Product with Serializable {
  def rev: Int
  def instant: Instant
}

object TestEvent {
  final case class Incremented(rev: Int, step: Int, instant: Instant) extends TestEvent
  final case class Initialized(rev: Int, instant: Instant)            extends TestEvent

  implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
  implicit val testEventCodec: Codec.AsObject[TestEvent] = deriveConfiguredCodec[TestEvent]

  val testEventEncoder: EventEncoder[TestEvent] = EventEncoder[TestEvent](
    _.rev,
    _.instant,
    None
  )

  val testEventDecoder: SingleDecoder[TestEvent] = PayloadDecoder[TestEvent](TestEntity.tpe)
}

sealed trait TestRejection extends Product with Serializable

object TestRejection {
  final case class InvalidRevision(rev: Int) extends TestRejection
}

sealed trait TestState extends Product with Serializable {
  def rev: Int
  def instant: Instant
}

object TestState {

  final case class Current(rev: Int, value: Int, instant: Instant) extends TestState

  implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
  implicit val testStateCodec: Codec.AsObject[TestState] = deriveConfiguredCodec[TestState]

  val testStateEncoder: StateEncoder[TestState] =
    StateEncoder[TestState](
      _.rev,
      _.instant
    )

  val testStateDecoder: SingleDecoder[TestState] = PayloadDecoder[TestState](TestEntity.tpe)
}

object TestEntity {

  val tpe: EntityType = EntityType("test")

  def serializer: EntitySerializer[TestEvent, TestState] = EntitySerializer(
    TestEvent.testEventEncoder,
    TestEvent.testEventDecoder,
    TestState.testStateEncoder,
    TestState.testStateDecoder
  )

  def persistentDefinition: PersistentDefinition[TestState, TestCommand, TestEvent, TestRejection] =
    PersistentDefinition(
      EntityType("increment"),
      EntityProcessor(
        None,
        evaluate,
        next
      ),
      (_: TestEvent) => Set("increment"),
      (_: TestEvent) => None,
      (_: TestEvent) => None
    )

  val next: (Option[TestState], TestEvent) => Option[TestState] = {
    case (None, Incremented(1, step, instant))             => Some(TestState.Current(1, step, instant))
    case (None, Initialized(rev, instant))                 => Some(TestState.Current(rev, 0, instant))
    case (Some(Current(_, value, _)), Incremented(rev, step, instant)) => Some(TestState.Current(rev, value + step , instant))
    case (Some(Current(_, _, _)), Initialized(rev, instant))           => Some(TestState.Current(rev, 0, instant))
    case (other, _)                                  => other
  }

  def evaluate(state: Option[TestState], cmd: TestCommand): IO[TestRejection, TestEvent] =
    (state, cmd) match {
      case (Some(Current(revS, _, _)), Boom(revC, message)) if revS == revC                  => IO.terminate(new RuntimeException(message))
      case (None, Boom(rev, message)) if rev == 0                                => IO.terminate(new RuntimeException(message))
      case (_, Boom(rev, _))                                                        => IO.raiseError(InvalidRevision(rev))
      case (Some(Current(revS, _, _)), Never(revC)) if revS == revC                          => IO.never
      case (None, Never(rev)) if rev == 0                                        => IO.never
      case (_, Never(rev))                                                          => IO.raiseError(InvalidRevision(rev))
      case (None, Increment(rev, step)) if rev == 0                              => IO.pure(Incremented(1, step, Instant.now()))
      case (None, Increment(rev, _))                                             => IO.raiseError(InvalidRevision(rev))
      case (None, IncrementAsync(rev, step, duration)) if rev == 0               =>
        IO.sleep(duration) >> IO.pure(Incremented(1, step, Instant.now()))
      case (None, IncrementAsync(rev, _, _))                                     => IO.raiseError(InvalidRevision(rev))
      case (None, Initialize(rev)) if rev == 0                                   => IO.pure(Initialized(1, Instant.now()))
      case (None, Initialize(rev))                                               => IO.raiseError(InvalidRevision(rev))
      case (Some(Current(revS, _, _)), Increment(revC, step)) if revS == revC                => IO.pure(Incremented(revS + 1, step, Instant.now()))
      case (Some(Current(_, _, _)), Increment(revC, _))                                      => IO.raiseError(InvalidRevision(revC))
      case (Some(Current(revS, _, _)), IncrementAsync(revC, step, duration)) if revS == revC =>
        IO.sleep(duration) >> IO.pure(Incremented(revS + 1, step, Instant.now()))
      case (Some(Current(_, _, _)), IncrementAsync(revC, _, duration))                       =>
        IO.sleep(duration) >> IO.raiseError(InvalidRevision(revC))
      case (Some(Current(revS, _, _)), Initialize(revC)) if revS == revC                     => IO.pure(Initialized(revS + 1, Instant.now()))
      case (Some(Current(_, _, _)), Initialize(rev))                                         => IO.raiseError(InvalidRevision(rev))
    }

}
