package ch.epfl.bluebrain.nexus.delta.sourcing

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticCommand.{Add, Boom, Never, Subtract}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticEvent.{Minus, Plus}
import ch.epfl.bluebrain.nexus.delta.sourcing.Arithmetic.ArithmeticRejection.NegativeTotal
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import monix.bio.IO

import java.time.Instant
import scala.annotation.nowarn

object Arithmetic {
  val entityType: EntityType = EntityType("calculator")

  val stateMachine: StateMachine[Total, ArithmeticCommand, ArithmeticEvent, ArithmeticRejection] = StateMachine(
    None,
    (state: Option[Total], command: ArithmeticCommand) =>
      (state, command) match {
        case (None, Add(value))         => IO.pure(Plus(1, value))
        case (Some(r), Add(value))      => IO.pure(Plus(r.rev + 1, value))
        case (None, Subtract(value))    => IO.raiseError(NegativeTotal(value * -1))
        case (Some(r), Subtract(value)) =>
          val newValue = r.value - value
          IO.raiseWhen(newValue < 0)(NegativeTotal(newValue)).as(Minus(r.rev + 1, value))
        case (_, Boom(message))         => IO.terminate(new RuntimeException(message))
        case (_, Never)                 => IO.never
      },
    (state: Option[Total], event: ArithmeticEvent) =>
      (state, event) match {
        case (None, p: Plus)     => Some(Total(1, p.value))
        case (None, _: Minus)    => None
        case (Some(r), p: Plus)  => Some(r.copy(value = r.value + p.value, rev = p.rev))
        case (Some(r), s: Minus) =>
          val newValue = r.value - s.value
          Option.when(newValue >= 0)(r.copy(value = r.value - s.value, rev = s.rev))
      }
  )

  sealed trait ArithmeticCommand extends Product with Serializable

  object ArithmeticCommand {
    final case class Add(value: Int)      extends ArithmeticCommand {
      require(value >= 0)
    }
    final case class Subtract(value: Int) extends ArithmeticCommand {
      require(value >= 0)
    }

    final case class Boom(message: String) extends ArithmeticCommand

    final case object Never extends ArithmeticCommand
  }

  sealed trait ArithmeticEvent extends GlobalEvent {
    def id: String
  }

  object ArithmeticEvent {
    final case class Plus(id: String, rev: Int, value: Int, instant: Instant, subject: Subject) extends ArithmeticEvent

    object Plus {
      def apply(rev: Int, value: Int): Plus = Plus("id", rev, value, Instant.EPOCH, Anonymous)
    }

    final case class Minus(id: String, rev: Int, value: Int, instant: Instant, subject: Subject) extends ArithmeticEvent

    object Minus {
      def apply(rev: Int, value: Int): Minus = Minus("id", rev, value, Instant.EPOCH, Anonymous)
    }

    @nowarn("cat=unused")
    val serializer: Serializer[String, ArithmeticEvent] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration           = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[ArithmeticEvent] = deriveConfiguredCodec[ArithmeticEvent]
      Serializer(_.id)
    }
  }

  sealed trait ArithmeticRejection extends Product with Serializable

  object ArithmeticRejection {
    final case class AlreadyExists(id: String, command: ArithmeticCommand) extends ArithmeticRejection
    final case class NegativeTotal(invalidValue: Int)                      extends ArithmeticRejection
  }

  final case class Total(
      id: String,
      rev: Int,
      value: Int,
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends GlobalState {
    override def deprecated: Boolean = false

    override def schema: ResourceRef = Latest(schemas + "arithmetic.json")

    override def types: Set[IriOrBNode.Iri] = Set(nxv + "Arithmetic")
  }

  object Total {
    @nowarn("cat=unused")
    val serializer: Serializer[String, Total] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[Total] = deriveConfiguredCodec[Total]
      Serializer(_.id)
    }

    def apply(rev: Int, value: Int): Total = Total("id", rev, value, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  }
}
