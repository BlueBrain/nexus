package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.persistence.query.{EventEnvelope, Sequence}
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.MessageSpec.{Event, Unexpected}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class MessageSpec extends AnyWordSpecLike with Matchers {

  "An Akka persistence enveloppe" should {

    val instant = Instant.now()
    val event   = Event("EventValue", instant)

    val enveloppe = EventEnvelope(
      Sequence(42L),
      "persistence-id",
      99L,
      event,
      25L
    )

    "be transformed just fine if the value has the proper type" in {
      Message[Event](enveloppe) shouldBe SuccessMessage(
        Sequence(42L),
        instant,
        "persistence-id",
        99L,
        event,
        Vector.empty
      )
    }

    "result in a CastFailedMessage if the event value is not of the expected type" in {
      Message[Unexpected](enveloppe) shouldBe CastFailedMessage(
        Sequence(42L),
        "persistence-id",
        99L,
        f"ch.epfl.bluebrain.nexus.delta.sourcing.projections.MessageSpec$$Unexpected",
        f"ch.epfl.bluebrain.nexus.delta.sourcing.projections.MessageSpec$$Event"
      )
    }
  }
}

object MessageSpec {
  final case class Event(text: String, instant: Instant)
  object Event      {
    implicit val eventLens: Lens[Event, Instant] = _.instant
  }
  final case class Unexpected(instant: Instant)
  object Unexpected {
    implicit val unexpecetdLens: Lens[Unexpected, Instant] = _.instant

  }
}
