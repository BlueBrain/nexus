package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.Http
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import io.circe.Decoder
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
    * Execute the argument request and an [[HttpResponse]].
    */
  def apply(req: HttpRequest): IO[HttpClientError, HttpResponse]

  /**
    * Execute the argument request and unmarshal the response into an A using a [[Decoder]].
    */
  def to[A: Decoder: ClassTag](req: HttpRequest): IO[HttpClientError, A]

  /**
    * Execute the argument request and return the stream of [[ByteString]].
    */
  def toDataBytes(req: HttpRequest): IO[HttpClientError, AkkaSource]

  /**
    * Execute the argument request, consume the response and ignore, returning the passed ''returnValue''
    * when the response is successful.
    */
  def discardBytes[A](req: HttpRequest, returnValue: => A): IO[HttpClientError, A]

}

object HttpClient {

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

      override def apply(req: HttpRequest): IO[HttpClientError, HttpResponse] =
        execute(req).retryingOnSomeErrors(httpConfig.isWorthRetrying)

      override def to[A: Decoder](req: HttpRequest)(implicit A: ClassTag[A]): IO[HttpClientError, A] =
        execute(req)
          .flatMap {
            case resp if resp.status.isSuccess() =>
              Task
                .deferFuture(decoderUnmarshaller.apply(resp.entity))
                .mapError(err => HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName))
            case resp                            =>
              consumeEntityOnError(req, resp)
          }
          .retryingOnSomeErrors(httpConfig.isWorthRetrying)

      override def toDataBytes(req: HttpRequest): IO[HttpClientError, AkkaSource] =
        execute(req)
          .flatMap {
            case resp if resp.status.isSuccess() => IO.delay(resp.entity.dataBytes).hideErrors
            case resp                            => consumeEntityOnError(req, resp)
          }
          .retryingOnSomeErrors(httpConfig.isWorthRetrying)

      override def discardBytes[A](req: HttpRequest, returnValue: => A): IO[HttpClientError, A] =
        execute(req)
          .flatMap {
            case resp if resp.status.isSuccess() => IO.pure(returnValue)
            case resp                            => consumeEntityOnError(req, resp)
          }
          .retryingOnSomeErrors(httpConfig.isWorthRetrying)

      private def consumeEntityOnError[A](req: HttpRequest, resp: HttpResponse): IO[HttpClientError, A] =
        Task
          .deferFuture(
            resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
          )
          .redeemCauseWith(
            error => IO.raiseError(HttpUnexpectedError(req, error.toThrowable.getMessage)),
            consumedString => IO.raiseError(HttpClientError.unsafe(req, resp.status, consumedString))
          )

      private def execute(req: HttpRequest): IO[HttpClientError, HttpResponse] =
        client.execute(req).mapError {
          case e: TimeoutException => HttpTimeoutError(req, e.getMessage)
          case e: Throwable        => HttpUnexpectedError(req, e.getMessage)
        }

    }
}