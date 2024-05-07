package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.error.Rejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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

import java.time.Instant

object Arithmetic {
  val entityType: EntityType = EntityType("calculator")

  val stateMachine: StateMachine[Total, ArithmeticCommand, ArithmeticEvent] = StateMachine(
    None,
    (state: Option[Total], command: ArithmeticCommand) =>
      (state, command) match {
        case (None, Add(value))         => IO.pure(Plus(1, value))
        case (Some(r), Add(value))      => IO.pure(Plus(r.rev + 1, value))
        case (None, Subtract(value))    => IO.raiseError(NegativeTotal(value * -1))
        case (Some(r), Subtract(value)) =>
          val newValue = r.value - value
          IO.raiseWhen(newValue < 0)(NegativeTotal(newValue)).as(Minus(r.rev + 1, value))
        case (_, Boom(message))         => IO.raiseError(new RuntimeException(message))
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
    def id: Iri
  }

  object ArithmeticEvent {
    final case class Plus(id: Iri, rev: Int, value: Int, instant: Instant, subject: Subject) extends ArithmeticEvent

    object Plus {
      def apply(rev: Int, value: Int): Plus = Plus(nxv + "id", rev, value, Instant.EPOCH, Anonymous)
    }

    final case class Minus(id: Iri, rev: Int, value: Int, instant: Instant, subject: Subject) extends ArithmeticEvent

    object Minus {
      def apply(rev: Int, value: Int): Minus = Minus(nxv + "id", rev, value, Instant.EPOCH, Anonymous)
    }

    val serializer: Serializer[Iri, ArithmeticEvent] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration           = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[ArithmeticEvent] = deriveConfiguredCodec[ArithmeticEvent]
      Serializer()
    }
  }

  sealed trait ArithmeticRejection extends Rejection {
    override def reason: String = this.toString
  }

  object ArithmeticRejection {
    final case object NotFound                                          extends ArithmeticRejection
    final case class RevisionNotFound(provided: Int, current: Int)      extends ArithmeticRejection
    final case class AlreadyExists(id: Iri, command: ArithmeticCommand) extends ArithmeticRejection
    final case class NegativeTotal(invalidValue: Int)                   extends ArithmeticRejection
  }

  final case class Total(
      id: Iri,
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

    val serializer: Serializer[Iri, Total] = {
      import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
      implicit val configuration: Configuration = Configuration.default.withDiscriminator("@type")
      implicit val coder: Codec.AsObject[Total] = deriveConfiguredCodec[Total]
      Serializer()
    }

    def apply(rev: Int, value: Int): Total =
      Total(nxv + "id", rev, value, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous)

  }
}
