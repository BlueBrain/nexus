package ch.epfl.bluebrain.nexus.delta.sdk.circe

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestMatchers}
import io.circe.DecodingFailure
import io.circe.syntax._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class CirceUnmarshallingSpec
    extends TestKit(ActorSystem("CirceUnmarshallingSpec"))
    with AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with CirceUnmarshalling
    with TestMatchers
    with ScalaFutures {

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
