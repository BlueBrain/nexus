package ch.epfl.bluebrain.nexus.delta.sdk.circe

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, MessageEntity}
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, TestMatchers}
import io.circe.syntax._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class CirceMarshallingSpec
    extends TestKit(ActorSystem("CirceMarshallingSpec"))
    with AnyWordSpecLike
    with Matchers
    with CirceLiteral
    with CirceMarshalling
    with TestMatchers
    with ScalaFutures {
  import system.dispatcher

  private val id       = nxv + "myresource"
  private val resource = SimpleResource(id, 1L, Instant.EPOCH, "Maria", 20)
  private val json     = json"""{"id": "$id", "rev": 1, "createdAt": ${Instant.EPOCH.asJson}, "name": "Maria", "age": 20}"""

  "Converting SimpleResource to an HttpEntity" should {

    "succeed" in {
      Marshal(resource).to[MessageEntity].futureValue shouldEqual HttpEntity(`application/json`, json.noSpaces)
    }
  }
}
