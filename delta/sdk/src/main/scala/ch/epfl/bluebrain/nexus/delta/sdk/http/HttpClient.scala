package ch.epfl.bluebrain.nexus.delta.sdk.http

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.{Http, HttpExt}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import io.circe.Decoder
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.TimeoutException
import scala.reflect.ClassTag

/**
  * Http client based on the akka http model.
  */
trait HttpClient {

  /**
    * Execute the argument request and unmarshal the response into an A using a [[Decoder]].
    *
    * @param req the request to execute
    */
  def to[A: Decoder](req: HttpRequest)(implicit A: ClassTag[A]): IO[HttpClientError, A] =
    apply[A](req)

  /**
    * Execute the argument request and unmarshal the response into an A.
    *
    * @param req the request to execute
    */
  def apply[A](
      req: HttpRequest
  )(implicit A: ClassTag[A], um: FromEntityUnmarshaller[A]): IO[HttpClientError, A]

  /**
    * Execute the argument request, consume the response and ignore, returning the passed ''returnValue''
    * when the response is successful.
    */
  def discardBytes[A](req: HttpRequest, returnValue: => A): IO[HttpClientError, A]

}

object HttpClient {

  /**
    * Construct the Http client using an underlying akka http client
    */
  final def apply(implicit as: ActorSystem, scheduler: Scheduler): HttpClient =
    new HttpClient {
      private val client: HttpExt = Http()

      private def execute(req: HttpRequest): IO[HttpClientError, HttpResponse] =
        Task
          .deferFuture(client.singleRequest(req))
          .mapError {
            case e: TimeoutException => HttpTimeoutError(req, e.getMessage)
            case e: Throwable        => HttpUnexpectedError(req, e.getMessage)
          }

      private def handleError[A](req: HttpRequest, resp: HttpResponse): IO[HttpClientError, A] =
        Task
          .deferFuture(
            resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
          )
          .mapError { e =>
            HttpUnexpectedError(req, e.getMessage)
          }
          .flatMap { err =>
            IO.raiseError(HttpClientError.unsafe(req, resp.status, err))
          }

      override def apply[A](
          req: HttpRequest
      )(implicit A: ClassTag[A], um: FromEntityUnmarshaller[A]): IO[HttpClientError, A] =
        execute(req).flatMap { resp =>
          if (resp.status.isSuccess())
            Task
              .deferFuture(um(resp.entity))
              .mapError(err => HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName))
          else
            handleError(req, resp)
        }

      override def discardBytes[A](req: HttpRequest, returnValue: => A): IO[HttpClientError, A] =
        execute(req).flatMap { resp =>
          if (resp.status.isSuccess())
            IO.pure(returnValue)
          else
            handleError(req, resp)
        }
    }
}
