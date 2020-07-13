package ch.epfl.bluebrain.nexus.commons.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMessage.DiscardedEntity
import akka.http.scaladsl.model.StatusCodes.ServerError
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.util.ByteString
import cats.MonadError
import cats.effect.{ContextShift, IO, LiftIO}
import cats.syntax.flatMap._
import com.typesafe.scalalogging.Logger

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.reflect.ClassTag

/**
  * Contract definition for an HTTP client based on the akka http model.
  *
  * @tparam F the monadic effect type
  * @tparam A the unmarshalled return type of a request
  */
trait HttpClient[F[_], A] {

  /**
    * Execute the argument request and unmarshal the response into an ''A''.
    *
    * @param req the request to execute
    * @return an unmarshalled ''A'' value in the ''F[_]'' context
    */
  def apply(req: HttpRequest): F[A]

  /**
    * Discard the response bytes of the entity, if any.
    *
    * @param entity the entity that needs to be discarded
    * @return a discarded entity
    */
  def discardBytes(entity: HttpEntity): F[DiscardedEntity]

  /**
    * Attempt to transform the entity bytes (if any) into an UTF-8 string representation.  If the entity has no bytes
    * an empty string will be returned instead.
    *
    * @param entity the entity to transform into a string representation
    * @return the entity bytes (if any) into an UTF-8 string representation
    */
  def toString(entity: HttpEntity): F[String]

  private[http] def handleError(req: HttpRequest, resp: HttpResponse, log: Logger)(implicit
      F: MonadError[F, Throwable]
  ): F[A] =
    toString(resp.entity).flatMap { body =>
      resp.status match {
        case _: ServerError         =>
          log.error(s"Server Error HTTP response for '${req.uri}', status: '${resp.status}', body: $body")
          F.raiseError(UnexpectedUnsuccessfulHttpResponse(resp, body))
        case StatusCodes.BadRequest =>
          log.warn(s"BadRequest HTTP response for '${req.uri}', body: $body")
          F.raiseError(UnexpectedUnsuccessfulHttpResponse(resp, body))
        case _                      =>
          log.debug(s"HTTP response for '${req.uri}', status: '${resp.status}', body: $body")
          F.raiseError(UnexpectedUnsuccessfulHttpResponse(resp, body))
      }
    }
}

// $COVERAGE-OFF$
object HttpClient {

  /**
    * Type alias for [[ch.epfl.bluebrain.nexus.commons.http.HttpClient]] that has the unmarshalled return type
    * the [[akka.http.scaladsl.model.HttpResponse]] itself.
    *
    * @tparam F the monadic effect type
    */
  type UntypedHttpClient[F[_]] = HttpClient[F, HttpResponse]

  /**
    * Constructs an [[ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient]] for an arbitrary effect
    * type using an underlying akka http client.
    *
    * @param L an implicit LiftIO instance
    * @param as an implicit actor system
    * @param mt an implicit materializer
    * @tparam F the effect type
    * @return an untyped http client based on akka http transport
    */
  final def untyped[F[_]](implicit L: LiftIO[F], as: ActorSystem, mt: Materializer): UntypedHttpClient[F] =
    new HttpClient[F, HttpResponse] {
      implicit private val ec: ExecutionContextExecutor   = as.dispatcher
      implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

      override def apply(req: HttpRequest): F[HttpResponse] =
        L.liftIO(IO.fromFuture(IO(Http().singleRequest(req))))

      override def discardBytes(entity: HttpEntity): F[DiscardedEntity] =
        L.liftIO(IO.pure(entity.discardBytes()))

      override def toString(entity: HttpEntity): F[String] =
        L.liftIO(IO.fromFuture(IO(entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String))))
    }

  /**
    * Constructs a typed ''HttpClient[F, A]'' from an ''UntypedHttpClient[F]'' by attempting to unmarshal the
    * response entity into the specific type ''A'' using an implicit ''FromEntityUnmarshaller[A]''.
    *
    * Delegates all calls to the underlying untyped http client.
    *
    * If the response status is not successful, the entity bytes will be discarded instead.
    *
    * @param L an implicit LiftIO instance
    * @param ec an implicit execution context
    * @param mt an implicit materializer
    * @param cl an implicit untyped http client
    * @param um an implicit ''FromEntityUnmarshaller[A]''
    * @tparam F the effect type
    * @tparam A the specific type to which the response entity should be unmarshalled into
    */
  final def withUnmarshaller[F[_], A: ClassTag](implicit
      L: LiftIO[F],
      F: MonadError[F, Throwable],
      ec: ExecutionContext,
      mt: Materializer,
      cl: UntypedHttpClient[F],
      um: FromEntityUnmarshaller[A]
  ): HttpClient[F, A] =
    new HttpClient[F, A] {
      implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)
      private val logger                                  = Logger(s"TypedHttpClient[${implicitly[ClassTag[A]]}]")

      override def apply(req: HttpRequest): F[A] =
        cl(req).flatMap { resp =>
          if (resp.status.isSuccess) L.liftIO(IO.fromFuture(IO(um(resp.entity))))
          else handleError(req, resp, logger)
        }

      override def discardBytes(entity: HttpEntity): F[DiscardedEntity] =
        cl.discardBytes(entity)

      override def toString(entity: HttpEntity): F[String] =
        cl.toString(entity)
    }

  /**
    * Default LiftIO instance for Scala Future.
    *
    * @param ec an implicitly available ExecutionContext
    */
  implicit def futureLiftIOInstance(implicit ec: ExecutionContext): LiftIO[Future] =
    new LiftIO[Future] {
      override def liftIO[A](ioa: IO[A]): Future[A] =
        ioa.attempt.unsafeToFuture().flatMap {
          case Right(a) => Future.successful(a)
          case Left(e)  => Future.failed(e)
        }
    }
}
// $COVERAGE-ON$
