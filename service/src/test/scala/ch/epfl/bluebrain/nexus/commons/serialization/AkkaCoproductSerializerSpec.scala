package ch.epfl.bluebrain.nexus.commons.serialization

import java.nio.charset.Charset

import ch.epfl.bluebrain.nexus.commons.serialization.AkkaCoproductSerializerSpec.Command.TheCommand
import ch.epfl.bluebrain.nexus.commons.serialization.AkkaCoproductSerializerSpec._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import shapeless._

class AkkaCoproductSerializerSpec extends AnyWordSpecLike with Matchers with Inspectors {

  "An AkkaCoproductSerializer" when {
    val ser  = new EventSerializer
    val UTF8 = Charset.forName("UTF-8")

    "computing a manifest" should {
      "provide the correct type" in {
        val mapping = Map[AnyRef, String](
          Event.Zero                    -> "Event",
          Event.One(12)                 -> "Event",
          Event.Two(12, Some("twelve")) -> "Event",
          Event.Two(12, None)           -> "Event",
          TheCommand(12)                -> "Command"
        )

        forAll(mapping.toList) {
          case (data, manifest) =>
            ser.manifest(data) shouldEqual manifest
        }
      }

      "throw an illegal argument exception for unknown types" in {
        intercept[IllegalArgumentException](ser.manifest("a"))
      }
    }

    "encoding to binary" should {
      "encode known events to UTF-8" in {
        val mapping = Map[AnyRef, String](
          Event.Zero                    -> """{"type":"Zero"}""",
          Event.One(12)                 -> """{"value":12,"type":"One"}""",
          Event.Two(12, Some("twelve")) -> """{"value":12,"text":"twelve","type":"Two"}""",
          Event.Two(12, None)           -> """{"value":12,"type":"Two"}""",
          TheCommand(12)                -> """{"arg":12,"type":"TheCommand"}"""
        )

        forAll(mapping.toList) {
          case (data, out) =>
            new String(ser.toBinary(data), UTF8) shouldEqual out
        }
      }
      "throw an illegal argument exception for unknown types" in {
        intercept[IllegalArgumentException](ser.toBinary("a"))
      }
    }

    "decoding from binary" should {
      "decode known events" in {
        val mapping = Map[(String, String), AnyRef](
          ("""{"type":"Zero"}""", "Event")                           -> Event.Zero,
          ("""{"value":12,"type":"One"}""", "Event")                 -> Event.One(12),
          ("""{"value":12,"text":"twelve","type":"Two"}""", "Event") -> Event.Two(12, Some("twelve")),
          ("""{"value":12,"type":"Two"}""", "Event")                 -> Event.Two(12, None),
          ("""{"arg":12,"type":"TheCommand"}""", "Command")          -> TheCommand(12)
        )

        forAll(mapping.toList) {
          case ((json, mf), obj) =>
            ser.fromBinary(json.getBytes(UTF8), mf) shouldEqual obj
        }
      }

      "throw an illegal argument exception for unknown manifest" in {
        intercept[IllegalArgumentException](ser.fromBinary("{}".getBytes(UTF8), "random"))
      }

      "throw an illegal argument exception for an incorrect json payload" in {
        intercept[IllegalArgumentException](ser.fromBinary("{}".getBytes(UTF8), "Event"))
      }
    }
  }

}

object AkkaCoproductSerializerSpec {

  sealed trait Event extends Product with Serializable
  object Event {
    final case object Zero                                 extends Event
    final case class One(value: Int)                       extends Event
    final case class Two(value: Int, text: Option[String]) extends Event
  }

  sealed trait Command extends Product with Serializable
  object Command {
    final case class TheCommand(arg: Int) extends Command
  }

  implicit private[serialization] val config: Configuration = Configuration.default.withDiscriminator("type")

  class EventSerializer extends AkkaCoproductSerializer[Command :+: Event :+: CNil](9210)
}
