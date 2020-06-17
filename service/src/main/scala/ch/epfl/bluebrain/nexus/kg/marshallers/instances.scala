package ch.epfl.bluebrain.nexus.kg.marshallers

import akka.http.scaladsl.marshalling.GenericMarshallers.eitherMarshaller
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.commons.http.directives.StatusFrom
import ch.epfl.bluebrain.nexus.kg.config.AppConfig._
import ch.epfl.bluebrain.nexus.kg.resources.Rejection
import ch.epfl.bluebrain.nexus.kg.resources.Rejection._
import ch.epfl.bluebrain.nexus.kg.routes.{RejectionEncoder, ResourceRedirect, TextOutputFormat}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe._
import io.circe.syntax._
import akka.http.scaladsl.server.Directives._

import scala.collection.immutable.Seq
import scala.concurrent.Future

object instances extends LowPriority {

  /**
    * `StatusCode, String` => HTTP response
    *
    * @return marshaller for string value
    */
  private def stringMarshaller(implicit output: TextOutputFormat): ToResponseMarshaller[(StatusCode, String)] = {
    val contentTypes: Seq[ContentType.NonBinary] =
      List(output.contentType, `text/plain`.toContentType(HttpCharsets.`UTF-8`))
    val marshallers                              = contentTypes.map(contentType =>
      Marshaller.withFixedContentType[(StatusCode, String), HttpResponse](contentType) {
        case (status, value) => HttpResponse(status = status, entity = HttpEntity(contentType, value))
      }
    )
    Marshaller.oneOf(marshallers: _*)
  }

  /**
    * `Either[Rejection,(StatusCode, String)]` => HTTP entity
    *
    * @return marshaller for any `A` value
    */
  implicit final def eitherStringMarshaller(implicit
      output: TextOutputFormat,
      printer: Printer = defaultPrinter
  ): ToResponseMarshaller[Either[Rejection, (StatusCode, String)]] =
    eitherMarshaller(valueWithStatusCodeFromMarshaller, stringMarshaller)

  /**
    * `Either[Rejection,(StatusCode, A)]` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def eitherValueMarshaller[A: Encoder](implicit
      printer: Printer = defaultPrinter
  ): ToResponseMarshaller[Either[Rejection, (StatusCode, A)]] =
    eitherMarshaller(valueWithStatusCodeFromMarshaller, valueWithStatusCodeMarshaller[A])

  /**
    * `Either[Rejection,A]` => HTTP entity
    *
    * @tparam A type to encode. This can at the same time return a rejection
    * @return marshaller for any `A` value
    */
  implicit final def eitherValueWithRejectionMarshaller[A](implicit
      encoder: RejectionEncoder[A],
      printer: Printer = defaultPrinter
  ): ToResponseMarshaller[Either[Rejection, (StatusCode, A)]] =
    Marshaller { implicit ec =>
      {
        case Left(rej)              => valueWithStatusCodeFromMarshaller.apply(rej)
        case Right((status, value)) =>
          encoder(value) match {
            case Left(rej)   => valueWithStatusCodeFromMarshaller.apply(rej)
            case Right(json) => jsonLdWithStatusCodeMarshaller.apply(status -> json)
          }
      }
    }

  /**
    * `A, StatusCodeFrom` => HTTP response
    *
    * @return marshaller for value
    */
  implicit final def valueWithStatusCodeFromMarshaller[A: Encoder](implicit
      statusFrom: StatusFrom[A],
      printer: Printer = defaultPrinter,
      ordered: OrderedKeys = orderedKeys
  ): ToResponseMarshaller[A] =
    jsonLdWithStatusCodeMarshaller.compose { value =>
      statusFrom(value) -> value.asJson
    }

  implicit class EitherFSyntax[F[_], R <: Rejection, A](f: F[Either[R, A]])(implicit F: Effect[F]) {
    def runWithStatus(code: StatusCode): Future[Either[R, (StatusCode, A)]]                                    =
      F.toIO(f.map(_.map(code -> _))).unsafeToFuture()

    def completeRedirect(code: Redirection = StatusCodes.SeeOther)(implicit ev: A =:= ResourceRedirect): Route =
      onSuccess(F.toIO(f).unsafeToFuture()) {
        case Left(r)  => complete(r: Rejection)
        case Right(v) => redirect(ev(v).value, code)
      }
  }

  implicit class FSyntax[F[_], A](f: F[A])(implicit F: Effect[F]) {
    def runWithStatus(code: StatusCode): Future[(StatusCode, A)] =
      F.toIO(f.map(code -> _)).unsafeToFuture()
  }

}

trait LowPriority extends FailFastCirceSupport {

  private[marshallers] val defaultPrinter = Printer.noSpaces.copy(dropNullValues = true)

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`)

  /**
    * `StatusCode, Json` => HTTP response
    *
    * @return marshaller for JSON-LD value
    */
  implicit final def jsonLdWithStatusCodeMarshaller(implicit
      printer: Printer = defaultPrinter,
      keys: OrderedKeys = orderedKeys
  ): ToResponseMarshaller[(StatusCode, Json)] =
    onOf(contentType =>
      Marshaller.withFixedContentType[(StatusCode, Json), HttpResponse](contentType) {
        case (status, json) =>
          HttpResponse(status = status, entity = HttpEntity(`application/ld+json`, printer.print(json.sortKeys)))
      }
    )

  /**
    * `Json` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  implicit final def jsonLdEntityMarshaller(implicit
      printer: Printer = defaultPrinter,
      keys: OrderedKeys = orderedKeys
  ): ToEntityMarshaller[Json] =
    onOf(contentType =>
      Marshaller.withFixedContentType[Json, MessageEntity](contentType) { json =>
        HttpEntity(`application/ld+json`, printer.print(json.sortKeys))
      }
    )

  /**
    * `A` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  implicit final def valueEntityMarshaller[A: Encoder](implicit
      printer: Printer = defaultPrinter,
      keys: OrderedKeys = orderedKeys
  ): ToEntityMarshaller[A] =
    jsonLdEntityMarshaller.compose(_.asJson)

  /**
    * `StatusCode, A` => HTTP response
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def valueWithStatusCodeMarshaller[A: Encoder](implicit
      printer: Printer = defaultPrinter,
      keys: OrderedKeys = orderedKeys
  ): ToResponseMarshaller[(StatusCode, A)] =
    jsonLdWithStatusCodeMarshaller.compose { case (status, value) => status -> value.asJson }

  private[marshallers] def onOf[A, Response](
      fMarshaller: MediaType.WithFixedCharset => Marshaller[A, Response]
  ): Marshaller[A, Response] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map(fMarshaller)
    Marshaller.oneOf(marshallers: _*)
  }
}
