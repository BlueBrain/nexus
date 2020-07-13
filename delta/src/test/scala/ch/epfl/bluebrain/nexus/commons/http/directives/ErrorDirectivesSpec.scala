package ch.epfl.bluebrain.nexus.commons.http.directives

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, StatusCodes}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.commons.circe.ContextUri
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes
import ch.epfl.bluebrain.nexus.commons.http.directives.ErrorDirectives._
import ch.epfl.bluebrain.nexus.commons.http.directives.ErrorDirectivesSpec.CustomError
import ch.epfl.bluebrain.nexus.rdf.syntax.iri._
import ch.epfl.bluebrain.nexus.util.ActorSystemFixture
import io.circe.generic.auto._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class ErrorDirectivesSpec
    extends ActorSystemFixture("ErrorDirectivesSpec")
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures {

  implicit override val patienceConfig = PatienceConfig(3.seconds, 100.millis)

  "A ErrorDirectives" should {
    import system.dispatcher
    implicit val statusFromJson: StatusFrom[CustomError] = StatusFrom((_: CustomError) => StatusCodes.NotFound)
    implicit val contextUri: ContextUri                  = ContextUri(url"http://localhost.com/error/")

    "marshall error JSON-LD" in {
      val error      = CustomError("some error")
      val jsonString = s"""{"@context":"${contextUri.value}","message":"${error.message}"}"""
      Marshal(error).to[HttpResponse].futureValue shouldEqual HttpResponse(
        status = StatusCodes.NotFound,
        entity = HttpEntity.Strict(RdfMediaTypes.`application/ld+json`, ByteString(jsonString, "UTF-8"))
      )
    }
  }

}

object ErrorDirectivesSpec {
  final case class CustomError(message: String)
}
