package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import akka.http.javadsl.model.headers.{AcceptEncoding, HttpEncodingRange}
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model.headers.HttpEncodings
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.StreamTcpException
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Json}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import retry.syntax.all._

import java.net.UnknownHostException
import scala.concurrent.TimeoutException
import scala.reflect.ClassTag

/**
  * Http client based on the akka http model.
  */
trait HttpClient {

  /**
    * Execute the request and evaluate the response using the passed ''pf''. If the response does not match the
    * ''pf'', consume the response and return the appropriate error
    */
  def apply[A](req: HttpRequest)(handleResponse: PartialFunction[HttpResponse, HttpResult[A]]): HttpResult[A]

  /**
    * Execute the argument request and unmarshal the response Json response.
    */
  def toJson(req: HttpRequest): HttpResult[Json] =
    fromJsonTo[Json](req)

  /**
    * Execute the argument request and unmarshal the Json response into an A using a [[Decoder]].
    */
  def fromJsonTo[A: Decoder: ClassTag](req: HttpRequest): HttpResult[A] =
    fromEntityTo(req)(decoderUnmarshaller[A], implicitly[ClassTag[A]])

  /**
    * Execute the argument request and unmarshal the response into an A using an [[Unmarshaller]].
    */
  def fromEntityTo[A: FromEntityUnmarshaller: ClassTag](req: HttpRequest): HttpResult[A]

  /**
    * Execute the argument request and return the stream of [[ByteString]].
    */
  def toDataBytes(req: HttpRequest): HttpResult[AkkaSource]

  /**
    * Execute the argument request, consume the response and ignore, returning the passed ''returnValue''
    * when the response is successful.
    */
  def discardBytes[A](req: HttpRequest, returnValue: => A): HttpResult[A]

}

object HttpClient {

  type HttpResult[A] = IO[HttpClientError, A]

  private val acceptEncoding =
    AcceptEncoding.create(HttpEncodingRange.create(HttpEncodings.gzip), HttpEncodingRange.create(HttpEncodings.deflate))

  private[http] trait HttpSingleRequest {
    def execute(request: HttpRequest): Task[HttpResponse]
  }

  private[http] object HttpSingleRequest {
    def default(implicit as: ActorSystem): HttpSingleRequest =
      (request: HttpRequest) => Task.deferFuture(Http().singleRequest(request))
  }

  /**
    * Construct the Http client using an underlying akka http client
    */
  final def apply()(implicit httpConfig: HttpClientConfig, as: ActorSystem, scheduler: Scheduler): HttpClient = {
    apply(HttpSingleRequest.default)
  }

  private[http] def apply(
      client: HttpSingleRequest
  )(implicit httpConfig: HttpClientConfig, as: ActorSystem, scheduler: Scheduler): HttpClient =
    new HttpClient {

      private def decodeResponse(req: HttpRequest, response: HttpResponse): IO[InvalidEncoding, HttpResponse] = {
        val decoder = response.encoding match {
          case HttpEncodings.gzip     => IO.pure(Coders.Gzip)
          case HttpEncodings.deflate  => IO.pure(Coders.Deflate)
          case HttpEncodings.identity => IO.pure(Coders.NoCoding)
          case encoding               => IO.raiseError(InvalidEncoding(req, encoding))
        }
        decoder.map(_.decodeMessage(response))
      }

      private val retryStrategy = httpConfig.strategy

      private def toHttpError(req: HttpRequest): Throwable => HttpClientError = {
        case e: TimeoutException                                                    => HttpTimeoutError(req, e.getMessage)
        case e: StreamTcpException if e.getCause.isInstanceOf[UnknownHostException] => HttpUnknownHost(req)
        case e: Throwable                                                           => HttpUnexpectedError(req, e.getMessage)
      }

      @SuppressWarnings(Array("IsInstanceOf"))
      override def apply[A](
          req: HttpRequest
      )(handleResponse: PartialFunction[HttpResponse, HttpResult[A]]): HttpResult[A] = {
        val reqCompressionSupport = if (httpConfig.compression) req.addHeader(acceptEncoding) else req
        for {
          encodedResp <- client.execute(reqCompressionSupport).mapError(toHttpError(req))
          resp        <- decodeResponse(req, encodedResp)
          a           <- handleResponse.applyOrElse(resp, resp => consumeEntity[A](req, resp))
        } yield a
      }.retryingOnSomeErrors(httpConfig.isWorthRetrying, retryStrategy.policy, retryStrategy.onError)

      override def fromEntityTo[A](
          req: HttpRequest
      )(implicit um: FromEntityUnmarshaller[A], A: ClassTag[A]): HttpResult[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            Task
              .deferFuture(um(resp.entity))
              .mapError(err => HttpSerializationError(req, err.getMessage, A.simpleName))
        }

      override def toDataBytes(req: HttpRequest): HttpResult[AkkaSource] =
        apply(req) {
          case resp if resp.status.isSuccess() => UIO.delay(resp.entity.dataBytes)
        }

      override def discardBytes[A](req: HttpRequest, returnValue: => A): HttpResult[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            UIO.delay(resp.discardEntityBytes()) >> IO.pure(returnValue)
        }

      private def consumeEntity[A](req: HttpRequest, resp: HttpResponse): HttpResult[A] =
        Task
          .deferFuture(
            resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
          )
          .redeemWith(
            error => IO.raiseError(HttpUnexpectedError(req, error.getMessage)),
            consumedString => IO.raiseError(HttpClientError(req, resp.status, consumedString))
          )

    }
}
