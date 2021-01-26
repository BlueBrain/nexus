package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `application/octet-stream`}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.OnceStrategyConfig
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpSingleRequest
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.{HttpClientStatusError, HttpSerializationError, HttpServerStatusError, HttpUnexpectedError}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientSpec.{Count, Value}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientWorthRetry.onServerError
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable, IOValues, TestHelpers}
import io.circe.generic.semiauto._
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

class HttpClientSpec
    extends TestKit(ActorSystem("HttpClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with IOValues
    with CirceLiteral
    with TestHelpers
    with ScalaFutures
    with BeforeAndAfterEach
    with EitherValuable {

  implicit private val config: HttpClientConfig = HttpClientConfig(OnceStrategyConfig(200.millis), onServerError)
  implicit private val sc: Scheduler            = Scheduler.global

  private val value1 = Value("first", 1, deprecated = false)
  private val value2 = Value("second", 2, deprecated = true)

  private val baseUri         = Uri("http://localhost/v1")
  private val reqGetValue     = HttpRequest(uri = baseUri / s"values/first")
  private val count           = Count()
  private val reqStreamValues = HttpRequest(uri = baseUri / "values/events")
  private val reqClientError  = HttpRequest(uri = baseUri / "values/errors/client")
  private val reqServerError  = HttpRequest(uri = baseUri / "values/errors/server")

  private def toSource(values: List[Json]): AkkaSource =
    Source(values.map(j => ByteString(j.noSpaces)))

  private def response(entity: ResponseEntity, status: StatusCode = OK): HttpResponse =
    HttpResponse(status = status, entity = entity)

  "An Http client" should {

    val httpSingleReq = new HttpSingleRequest {
      override def execute(request: HttpRequest): Task[HttpResponse] =
        request match {
          case `reqGetValue`     =>
            Task.delay(count.reqGetValue.incrementAndGet()) >>
              Task(response(HttpEntity(`application/json`, value1.asJson.noSpaces)))
          case `reqStreamValues` =>
            Task.delay(count.reqStreamValues.incrementAndGet()) >>
              Task(response(HttpEntity(`application/octet-stream`, toSource(List(value1.asJson, value2.asJson)))))
          case `reqClientError`  =>
            Task.delay(count.reqClientError.incrementAndGet()) >>
              Task(response(HttpEntity(`application/json`, json"""{"error": "client"}""".noSpaces), BadRequest))
          case `reqServerError`  =>
            Task.delay(count.reqServerError.incrementAndGet()) >>
              Task(
                response(HttpEntity(`application/json`, json"""{"error": "server"}""".noSpaces), InternalServerError)
              )
          case _                 =>
            Task.delay(count.reqOtherError.incrementAndGet()) >>
              Task.raiseError(new IllegalArgumentException("wrong request"))
        }
    }

    val client = HttpClient(httpSingleReq)

    "return the Value response" in {
      client.fromJsonTo[Value](reqGetValue).accepted shouldEqual value1
      count.values shouldEqual Count(reqGetValue = 1).values
    }

    "return the AkkaSource response" in {
      val stream = client.toDataBytes(reqStreamValues).accepted
      stream
        .runFold(Vector.empty[Value]) { (acc, c) => acc :+ parse(c.utf8String).rightValue.as[Value].rightValue }
        .futureValue shouldEqual Vector(value1, value2)
      count.values shouldEqual Count(reqStreamValues = 1).values
    }

    "fail Decoding the Int response" in {
      client.fromJsonTo[Int](reqGetValue).rejectedWith[HttpSerializationError]
      count.values shouldEqual Count(reqGetValue = 1).values
    }

    "fail with HttpUnexpectedError while retrying" in {
      client.toJson(HttpRequest(uri = "http://other.com")).rejectedWith[HttpUnexpectedError]
      count.values shouldEqual Count(reqOtherError = 2).values
    }

    "fail with HttpServerStatusError while retrying" in {
      client.toJson(reqServerError).rejectedWith[HttpServerStatusError]
      count.values shouldEqual Count(reqServerError = 2).values
    }

    "fail with HttpClientStatusError" in {
      client.toJson(reqClientError).rejectedWith[HttpClientStatusError]
      count.values shouldEqual Count(reqClientError = 1).values
    }
  }

  override protected def beforeEach(): Unit =
    count.clear()
}

object HttpClientSpec {
  final case class Value(name: String, rev: Long, deprecated: Boolean)

  final case class Count(
      reqGetValue: AtomicInteger,
      reqStreamValues: AtomicInteger,
      reqClientError: AtomicInteger,
      reqServerError: AtomicInteger,
      reqOtherError: AtomicInteger
  ) {

    def values: (Int, Int, Int, Int, Int) =
      (reqGetValue.get, reqStreamValues.get, reqClientError.get, reqServerError.get, reqOtherError.get)

    def clear(): Unit = {
      reqGetValue.set(0)
      reqStreamValues.set(0)
      reqClientError.set(0)
      reqServerError.set(0)
      reqOtherError.set(0)
    }
  }
  object Count {
    def apply(
        reqGetValue: Int = 0,
        reqStreamValues: Int = 0,
        reqClientError: Int = 0,
        reqServerError: Int = 0,
        reqOtherError: Int = 0
    ): Count =
      Count(
        new AtomicInteger(reqGetValue),
        new AtomicInteger(reqStreamValues),
        new AtomicInteger(reqClientError),
        new AtomicInteger(reqServerError),
        new AtomicInteger(reqOtherError)
      )
  }

  object Value {
    implicit val valueDecoder: Decoder[Value] = deriveDecoder
    implicit val valueEncoder: Encoder[Value] = deriveEncoder

  }
}
