package ch.epfl.bluebrain.nexus.delta.routes.marshalling

import java.time.Instant
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import ch.epfl.bluebrain.nexus.delta.SimpleResource
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestMatchers}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.syntax._
import io.circe.{Decoder, DecodingFailure}
import org.scalatest.matchers.should.Matchers

class CirceUnmarshallingSpec
    extends RouteHelpers
    with Matchers
    with CirceLiteral
    with CirceUnmarshalling
    with TestMatchers {

  implicit private val simpleResourceDecoder: Decoder[SimpleResource] = deriveDecoder[SimpleResource]

  private val id       = nxv + "myresource"
  private val resource = SimpleResource(id, 1L, Instant.EPOCH, "Maria", 20)
  private val json     = json"""{"id": "$id", "rev": 1, "createdAt": ${Instant.EPOCH.asJson}, "name": "Maria", "age": 20}"""

  "Converting HttpEntity to a SimpleResource" should {

    "succeed" in {
      val entity = HttpEntity(`application/json`, json.noSpaces)
      Unmarshal(entity).to[SimpleResource].futureValue shouldEqual resource
    }

    "fail" in {
      val entity = HttpEntity(`application/json`, json"""{"k": "v"}""".noSpaces)
      Unmarshal(entity).to[SimpleResource].failed.futureValue shouldBe a[DecodingFailure]
    }
  }

  "Converting HttpRequest to a SimpleResource" should {

    "succeed" in {
      val request = HttpRequest(entity = HttpEntity(`application/json`, json.noSpaces))
      Unmarshal(request).to[SimpleResource].futureValue shouldEqual resource
    }

    "fail" in {
      val request = HttpRequest(entity = HttpEntity(`application/json`, json"""{"k": "v"}""".noSpaces))
      Unmarshal(request).to[SimpleResource].failed.futureValue shouldBe a[DecodingFailure]
    }
  }
}
