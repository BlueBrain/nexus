package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.persistence.query.{EventEnvelope, Sequence}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MessageSpec extends AnyWordSpecLike with Matchers {

  "An Akka persistence enveloppe" should {

    val enveloppe = EventEnvelope(
      Sequence(42L),
      "persistence-id",
      99L,
      "EventValue",
      25L
    )

    "be transformed just fine if the value has the proper type" in {
      Message[String](enveloppe) shouldBe SuccessMessage(
        Sequence(42L),
        "persistence-id",
        99L,
        "EventValue"
      )
    }

    "result in a CastFailedMessage if the event value is not of the expected type" in {
      Message[Int](enveloppe) shouldBe CastFailedMessage(
        Sequence(42L),
        "persistence-id",
        99L,
        "int",
        "java.lang.String"
      )
    }
  }
}
