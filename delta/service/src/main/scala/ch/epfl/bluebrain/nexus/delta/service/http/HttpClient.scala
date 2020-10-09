package ch.epfl.bluebrain.nexus.delta.service.http

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.service.http.HttpClientError.{HttpSerializationError, HttpTimeoutError, HttpUnexpectedError}
import monix.bio.{IO, Task}
import monix.execution.Scheduler

import scala.concurrent.TimeoutException
import scala.reflect.ClassTag

/**
  * Http client based on the akka http model.
  */
trait HttpClient {

  /**
    * Execute the argument request and unmarshal the response into an A.
    *
   * @param req the request to execute
    */
  def apply[A](req: HttpRequest)(implicit A: ClassTag[A], um: FromEntityUnmarshaller[A]): IO[HttpClientError, A]

}

object HttpClient {

  /**
    * Construct the Http client using an underlying akka http client
    */
  def apply(implicit as: ActorSystem, materializer: Materializer, scheduler: Scheduler): HttpClient =
    new HttpClient {
      private val client: HttpExt = Http()

      override def apply[A](
          req: HttpRequest
      )(implicit A: ClassTag[A], um: FromEntityUnmarshaller[A]): IO[HttpClientError, A] =
        Task
          .deferFuture(
            client.singleRequest(req)
          )
          .mapError {
            case e: TimeoutException => HttpTimeoutError(req, e.getMessage)
            case e: Throwable        => HttpUnexpectedError(req, e.getMessage)
          }
          .flatMap { res =>
            if (res.status.isSuccess()) {
              Task
                .deferFuture {
                  um(res.entity)
                }
                .onErrorHandleWith { err =>
                  IO.raiseError(
                    HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName)
                  )
                }
            } else {
              Task
                .deferFuture(
                  res.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
                )
                .mapError { e =>
                  HttpUnexpectedError(req, e.getMessage)
                }
                .flatMap { err =>
                  IO.raiseError(HttpClientError.unsafe(req, res.status, err))
                }
            }
          }
    }
}
