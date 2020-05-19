package ch.epfl.bluebrain.nexus.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.util.ByteString
import cats.effect.{ContextShift, Effect, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.clients.ClientError.HttpClientError
import ch.epfl.bluebrain.nexus.clients.ClientError.HttpClientError.HttpSerializationError
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport.{DecodingFailures => AccDecodingFailures}
import io.circe.Error

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag

/**
  * Contract definition for an HTTP client based on the akka http model.
  *
  * @tparam F the monadic effect type
  */
//TODO: Convert signatures bifunctor F[_, _] and change raise errors to the left side
trait HttpClient[F[_]] {

  /**
    * Execute the argument request and unmarshal the response into an ''A'' when the response is successful.
    * Raise an ''HttpError'' on the ''F[_]'' when the HTTP status code is not successful.
    *
    * @return an unmarshalled ''A'' value in the ''F[_]'' context
    */
  def executeParse[A: ClassTag: FromEntityUnmarshaller](req: HttpRequest): F[A]

  /**
    * Execute the argument request, consume the response and ignore, returning the passed ''returnValue''
    * when the response is successful.
    * Raise an ''HttpError'' on the ''F[_]'' when the HTTP status code is not successful.
    *
    * @return an unmarshalled ''A'' value in the ''F[_]'' context
    */
  def executeDiscard[A: ClassTag](req: HttpRequest, returnValue: => A): F[A]
}

object HttpClient {

  // TODO: Add RetryStrategyConfig with condition as parameter and perform retries (after changed signature to F[_, _]
  final def apply[F[_]](implicit as: ActorSystem, F: Effect[F]): HttpClient[F] = new HttpClient[F] {

    implicit private val ec: ExecutionContextExecutor   = as.dispatcher
    implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

    private def execute[A](req: HttpRequest, f: HttpResponse => F[A])(implicit A: ClassTag[A]): F[A] = {
      IO.fromFuture(IO(Http().singleRequest(req))).to[F].flatMap { resp =>
        if (resp.status.isSuccess())
          f(resp).recoverWith {
            case err: AccDecodingFailures =>
              F.raiseError(HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName))
            case err: Error =>
              F.raiseError(HttpSerializationError(req, err.getMessage, A.runtimeClass.getSimpleName))
          }
        else
          IO.fromFuture(IO(resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)))
            .to[F]
            .flatMap { err => F.raiseError(HttpClientError.unsafe(req, resp.status, err)) }
      }
    }

    override def executeParse[A: ClassTag](req: HttpRequest)(implicit um: FromEntityUnmarshaller[A]): F[A] =
      execute(req, resp => IO.fromFuture(IO(um(resp.entity))).to[F])

    override def executeDiscard[A: ClassTag](req: HttpRequest, returnValue: => A): F[A] =
      execute(req, _ => F.pure(returnValue))
  }
}
