package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import com.datastax.oss.driver.api.core.uuid.Uuids
import io.circe.Json
import io.circe.syntax._
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OffsetSpec extends AnyWordSpec with Matchers with Inspectors with CirceLiteral with EitherValuable {

  "An Offset" should {
    val timeOffset                   = TimeBasedUUID(Uuids.timeBased())
    val time                         = OffsetUtils.toInstant(timeOffset)
    val timeOffsetJson               = json"""{"@type": "TimeBasedOffset", "value": "${timeOffset.value}", "instant": "$time"}"""
    val sequenceOffset               = Sequence(1)
    val sequenceOffsetJson           = json"""{"@type": "SequenceBasedOffset", "value": ${sequenceOffset.value}}"""
    val noOffset                     = NoOffset
    val noOffsetJson                 = json"""{"@type": "NoOffset"}"""
    val values: List[(Offset, Json)] =
      List(timeOffset -> timeOffsetJson, sequenceOffset -> sequenceOffsetJson, noOffset -> noOffsetJson)

    "be converted to json" in {
      forAll(values) { case (v, json) =>
        v.asJson shouldEqual json
      }
    }

    "be created from json" in {
      forAll(values) { case (v, json) =>
        json.as[Offset].rightValue shouldEqual v
      }
    }

    "be ordered" in {
      val timeOffset2     = TimeBasedUUID(Uuids.timeBased())
      val sequenceOffset2 = Sequence(2)
      List[Offset](timeOffset, timeOffset2, noOffset).sorted shouldEqual
        List(noOffset, timeOffset, timeOffset2)

      List[Offset](sequenceOffset, sequenceOffset2, noOffset).sorted shouldEqual
        List(noOffset, sequenceOffset, sequenceOffset2)
    }
  }

}
