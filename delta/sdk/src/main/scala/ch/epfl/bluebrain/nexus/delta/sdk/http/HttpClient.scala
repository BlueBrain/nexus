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
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Json}

import java.net.UnknownHostException
import scala.concurrent.{ExecutionContext, TimeoutException}
import scala.reflect.ClassTag

/**
  * Http client based on the akka http model.
  */
trait HttpClient {

  /**
    * Execute the request and evaluate the response using the passed ''pf''. If the response does not match the ''pf'',
    * consume the response and return the appropriate error
    */
  def apply[A](req: HttpRequest)(handleResponse: PartialFunction[HttpResponse, IO[A]]): IO[A]

  def run[A](req: HttpRequest)(handleResponse: PartialFunction[HttpResponse, IO[A]]): IO[A] =
    apply(req) { case r if handleResponse.isDefinedAt(r) => handleResponse(r) }

  /**
    * Execute the argument request and unmarshal the response Json response.
    */
  def toJson(req: HttpRequest): IO[Json]                                                    =
    fromJsonTo[Json](req)

  /**
    * Execute the argument request and unmarshal the Json response into an A using a [[Decoder]].
    */
  def fromJsonTo[A: Decoder: ClassTag](req: HttpRequest): IO[A] =
    fromEntityTo(req)(decoderUnmarshaller[A], implicitly[ClassTag[A]])

  /**
    * Execute the argument request and unmarshal the response into an A using an [[Unmarshaller]].
    */
  def fromEntityTo[A: FromEntityUnmarshaller: ClassTag](req: HttpRequest): IO[A]

  /**
    * Execute the argument request and return the stream of [[ByteString]].
    */
  def toDataBytes(req: HttpRequest): IO[AkkaSource]

  /**
    * Execute the argument request, consume the response and ignore, returning the passed ''returnValue'' when the
    * response is successful.
    */
  def discardBytes[A](req: HttpRequest, returnValue: => A): IO[A]

}

object HttpClient {

  private val acceptEncoding =
    AcceptEncoding.create(HttpEncodingRange.create(HttpEncodings.gzip), HttpEncodingRange.create(HttpEncodings.deflate))

  private[http] trait HttpSingleRequest {
    def execute(request: HttpRequest): IO[HttpResponse]
  }

  private[http] object HttpSingleRequest {
    def default(implicit as: ActorSystem): HttpSingleRequest =
      (request: HttpRequest) => IO.fromFuture(IO.delay(Http().singleRequest(request)))
  }

  /**
    * Construct the Http client using an underlying akka http client
    */
  final def apply()(implicit httpConfig: HttpClientConfig, as: ActorSystem): HttpClient = {
    apply(HttpSingleRequest.default)
  }

  /**
    * Construct an Http client using an underlying akka http client which will not retry on failures
    */
  final def noRetry(
      compression: Boolean
  )(implicit as: ActorSystem): HttpClient = {
    implicit val config: HttpClientConfig = HttpClientConfig.noRetry(compression)
    apply()
  }

  private[http] def apply(
      client: HttpSingleRequest
  )(implicit httpConfig: HttpClientConfig, as: ActorSystem): HttpClient =
    new HttpClient {
      implicit private val ec: ExecutionContext = as.dispatcher

      private def decodeResponse(req: HttpRequest, response: HttpResponse): IO[HttpResponse] = {
        val decoder = response.encoding match {
          case HttpEncodings.gzip     => IO.pure(Coders.Gzip)
          case HttpEncodings.deflate  => IO.pure(Coders.Deflate)
          case HttpEncodings.identity => IO.pure(Coders.NoCoding)
          case encoding               => IO.raiseError(InvalidEncoding(req, encoding))
        }
        decoder.map(_.decodeMessage(response))
      }

      @SuppressWarnings(Array("IsInstanceOf"))
      private def toHttpError(req: HttpRequest): Throwable => HttpClientError = {
        case e: TimeoutException                                                    => HttpTimeoutError(req, e.getMessage)
        case e: StreamTcpException if e.getCause.isInstanceOf[UnknownHostException] => HttpUnknownHost(req)
        case e: Throwable                                                           => HttpUnexpectedError(req, e.getMessage)
      }

      override def apply[A](
          req: HttpRequest
      )(handleResponse: PartialFunction[HttpResponse, IO[A]]): IO[A] = {
        val reqCompressionSupport =
          if (httpConfig.compression) {
            Coders.Gzip.encodeMessage(req).addHeader(acceptEncoding)
          } else
            req.addHeader(acceptEncoding)

        for {
          encodedResp <- client.execute(reqCompressionSupport).adaptError(toHttpError(reqCompressionSupport)(_))
          resp        <- decodeResponse(reqCompressionSupport, encodedResp)
          a           <- handleResponse.applyOrElse(resp, resp => consumeEntity[A](reqCompressionSupport, resp))
        } yield a
      }.retry(httpConfig.strategy)

      override def fromEntityTo[A](
          req: HttpRequest
      )(implicit um: FromEntityUnmarshaller[A], A: ClassTag[A]): IO[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            IO
              .fromFuture(IO.delay(um(resp.entity)))
              .adaptError(err => HttpSerializationError(req, err.getMessage, A.simpleName))
        }

      override def toDataBytes(req: HttpRequest): IO[AkkaSource] =
        apply(req) {
          case resp if resp.status.isSuccess() => IO.delay(resp.entity.dataBytes)
        }

      override def discardBytes[A](req: HttpRequest, returnValue: => A): IO[A] =
        apply(req) {
          case resp if resp.status.isSuccess() =>
            IO.delay(resp.discardEntityBytes()) >> IO.pure(returnValue)
        }

      private def consumeEntity[A](req: HttpRequest, resp: HttpResponse): IO[A] =
        IO.fromFuture(
          IO.delay(
            resp.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)
          )
        ).redeemWith(
          error => IO.raiseError(HttpUnexpectedError(req, error.getMessage)),
          consumedString => IO.raiseError(HttpClientError(req, resp.status, consumedString))
        )
    }
}
