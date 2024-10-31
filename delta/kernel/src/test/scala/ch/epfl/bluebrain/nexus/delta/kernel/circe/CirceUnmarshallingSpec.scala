package ch.epfl.bluebrain.nexus.delta.kernel.circe

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKit
import io.circe.DecodingFailure
import io.circe.literal._
import io.circe.syntax._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class CirceUnmarshallingSpec
    extends TestKit(ActorSystem("CirceUnmarshallingSpec"))
    with AnyWordSpecLike
    with Matchers
    with CirceUnmarshalling
    with ScalaFutures {

  private val id       = "myresource"
  private val resource = SimpleResource(id, 1, Instant.EPOCH, "Maria", 20)
  private val json     = json"""{"id": $id, "rev": 1, "createdAt": ${Instant.EPOCH.asJson}, "name": "Maria", "age": 20}"""

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
