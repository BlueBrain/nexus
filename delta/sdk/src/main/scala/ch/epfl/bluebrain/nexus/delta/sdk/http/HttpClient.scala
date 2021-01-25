package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import io.circe.{Decoder, Json}
import monix.bio.{IO, Task}
import monix.execution.Scheduler
import retry.CatsEffect._
import retry.syntax.all._

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
  final def apply()(implicit httpConfig: HttpClientConfig, as: ActorSystem, scheduler: Scheduler): HttpClient =
    apply(HttpSingleRequest.default)

  private[http] def apply(
      client: HttpSingleRequest
  )(implicit httpConfig: HttpClientConfig, as: ActorSystem, scheduler: Scheduler): HttpClient =
    new HttpClient {

      private val retryStrategy = httpConfig.strategy
      import retryStrategy._

      override def apply[A](
          req: HttpRequest
      )(handleResponse: PartialFunction[HttpResponse, HttpResult[A]]): HttpResult[A] =
        client
          .execute(req)
          .mapError {
            case e: TimeoutException => HttpTimeoutError(req, e.getMessage)
            case e: Throwable        => HttpUnexpectedError(req, e.getMessage)
          }
          .flatMap(resp => handleResponse.applyOrElse(resp, resp => consumeEntity[A](req, resp)))
          .retryingOnSomeErrors(httpConfig.isWorthRetrying)

      override def fromEntityTo[A](
          req: HttpRequest
      )(implicit um: FromEntityUnmarshaller[A], A: ClassTag[A]): HttpResult[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            Task
              .deferFuture(um(resp.entity))
              .mapError(err => HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName))
        }

      override def toDataBytes(req: HttpRequest): HttpResult[AkkaSource] =
        apply(req) {
          case resp if resp.status.isSuccess() => IO.delay(resp.entity.dataBytes).hideErrors
        }

      override def discardBytes[A](req: HttpRequest, returnValue: => A): HttpResult[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(returnValue)
        }

      private def consumeEntity[A](req: HttpRequest, resp: HttpResponse): HttpResult[A] =
        Task
          .deferFuture(
            resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
          )
          .redeemCauseWith(
            error => IO.raiseError(HttpUnexpectedError(req, error.toThrowable.getMessage)),
            consumedString => IO.raiseError(HttpClientError(req, resp.status, consumedString))
          )

    }
}
