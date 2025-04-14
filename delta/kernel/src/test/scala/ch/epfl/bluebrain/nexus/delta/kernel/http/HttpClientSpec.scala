package ch.epfl.bluebrain.nexus.delta.kernel.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.MediaTypes.{`application/json`, `application/octet-stream`}
import akka.http.scaladsl.model.StatusCodes.*
import akka.http.scaladsl.model.*
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import akka.util.ByteString
import cats.effect.IO
import cats.effect.unsafe.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.AkkaSource
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategyConfig.OnceStrategyConfig
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClient.HttpSingleRequest
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError.{HttpClientStatusError, HttpSerializationError, HttpServerStatusError, HttpUnexpectedError}
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientSpec.{Count, Value}
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientWorthRetry.onServerError
import io.circe.generic.semiauto.*
import io.circe.literal.*
import io.circe.parser.parse
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, Json}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterEach}

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.*

class HttpClientSpec
    extends TestKit(ActorSystem("HttpClientSpec"))
    with AsyncWordSpecLike
    with BeforeAndAfterEach
    with Matchers {

  implicit def ioToFutureAssertion(io: IO[Assertion]): Future[Assertion] = io.unsafeToFuture()

  implicit private val config: HttpClientConfig = HttpClientConfig(OnceStrategyConfig(200.millis), onServerError, false)

  private val value1 = Value("first", 1, deprecated = false)
  private val value2 = Value("second", 2, deprecated = true)

  private val baseUri         = "http://localhost/v1"
  private val getUri          = Uri(s"$baseUri/values/first")
  private val reqGetValue     = HttpRequest(uri = getUri)
  private val count           = Count()
  private val streamUri       = Uri(s"$baseUri/values/events")
  private val reqStreamValues = HttpRequest(uri = streamUri)
  private val clientErrorUri  = Uri(s"$baseUri/values/errors/client")
  private val reqClientError  = HttpRequest(uri = clientErrorUri)
  private val serverErrorUri  = Uri(s"$baseUri/values/errors/server")
  private val reqServerError  = HttpRequest(uri = serverErrorUri)

  private def toSource(values: List[Json]): AkkaSource =
    Source(values.map(j => ByteString(j.noSpaces)))

  private def response(entity: ResponseEntity, status: StatusCode = OK): HttpResponse =
    HttpResponse(status = status, entity = entity)

  "An Http client" should {

    val httpSingleReq = new HttpSingleRequest {
      override def execute(request: HttpRequest): IO[HttpResponse] =
        request.uri match {
          case `getUri`         =>
            IO.delay(count.reqGetValue.incrementAndGet()) >>
              IO.delay(response(HttpEntity(`application/json`, value1.asJson.noSpaces)))
          case `streamUri`      =>
            IO.delay(count.reqStreamValues.incrementAndGet()) >>
              IO.delay(response(HttpEntity(`application/octet-stream`, toSource(List(value1.asJson, value2.asJson)))))
          case `clientErrorUri` =>
            IO.delay(count.reqClientError.incrementAndGet()) >>
              IO.delay(response(HttpEntity(`application/json`, json"""{"error": "client"}""".noSpaces), BadRequest))
          case `serverErrorUri` =>
            IO.delay(count.reqServerError.incrementAndGet()) >>
              IO.delay(
                response(HttpEntity(`application/json`, json"""{"error": "server"}""".noSpaces), InternalServerError)
              )
          case _                =>
            IO.delay(count.reqOtherError.incrementAndGet()) >>
              IO.raiseError(new IllegalArgumentException("wrong request"))
        }
    }

    val client = HttpClient(httpSingleReq)

    "return the Value response" in {
      client.fromJsonTo[Value](reqGetValue).map { obtained =>
        obtained shouldEqual value1
        count.values shouldEqual Count(reqGetValue = 1).values
      }
    }

    "return the AkkaSource response" in {
      def parseValue(value: String) =
        parse(value).flatMap(_.as[Value]).getOrElse(throw new IllegalStateException("Could not be parsed to value"))

      def sourceToVector(source: AkkaSource) = source
        .runFold(Vector.empty[Value]) { (acc, c) => acc :+ parseValue(c.utf8String) }
      for {
        source <- client.toDataBytes(reqStreamValues)
        values <- IO.fromFuture(IO.delay(sourceToVector(source)))
      } yield {
        values shouldEqual Vector(value1, value2)
        count.values shouldEqual Count(reqStreamValues = 1).values
      }
    }

    "fail Decoding the Int response" in {
      recoverToSucceededIf[HttpSerializationError] {
        client.fromJsonTo[Int](reqGetValue).map(_ => succeed)
      }.map { _ =>
        count.values shouldEqual Count(reqGetValue = 1).values
      }
    }

    "fail with HttpUnexpectedError while retrying" in {
      recoverToSucceededIf[HttpUnexpectedError] {
        client.toJson(HttpRequest(uri = "http://other.com")).map(_ => succeed)
      }.map { _ =>
        count.values shouldEqual Count(reqOtherError = 2).values
      }
    }

    "fail with HttpServerStatusError while retrying" in {
      recoverToSucceededIf[HttpServerStatusError] {
        client.toJson(reqServerError).map(_ => succeed)
      }.map { _ =>
        count.values shouldEqual Count(reqServerError = 2).values
      }
    }

    "fail with HttpClientStatusError" in {
      recoverToSucceededIf[HttpClientStatusError] {
        client.toJson(reqClientError).map(_ => succeed)
      }.map { _ =>
        count.values shouldEqual Count(reqClientError = 1).values
      }
    }
  }

  override protected def beforeEach(): Unit =
    count.clear()
}

object HttpClientSpec {
  final case class Value(name: String, rev: Int, deprecated: Boolean)

  final private case class Count(
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
  private object Count {
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
