package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.marshalling.GenericMarshallers.eitherMarshaller
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._
import ch.epfl.bluebrain.nexus.storage.JsonLdCirceSupport.sortKeys
import ch.epfl.bluebrain.nexus.storage.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.storage.Rejection
import ch.epfl.bluebrain.nexus.storage.config.AppConfig._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe._
import io.circe.syntax._
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.immutable.Seq
import scala.concurrent.Future

object instances extends LowPriority {

  /**
    * `Either[Rejection,(StatusCode, A)]` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def eitherValueMarshaller[A: Encoder](implicit
      printer: Printer = defaultPrinter
  ): ToResponseMarshaller[Either[Rejection, (StatusCode, A)]] =
    eitherMarshaller(valueWithStatusCodeFromMarshaller[Rejection], valueWithStatusCodeMarshaller[A])

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

  implicit final class EitherFSyntax[A](f: Task[Either[Rejection, A]])(implicit scheduler: Scheduler) {
    def runWithStatus(code: StatusCode): Future[Either[Rejection, (StatusCode, A)]] =
      f.map(_.map(code -> _)).runToFuture
  }

}

trait LowPriority extends FailFastCirceSupport {

  private[routes] val defaultPrinter = Printer.noSpaces.copy(dropNullValues = true)

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`)

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
      Marshaller.withFixedContentType[(StatusCode, Json), HttpResponse](contentType) { case (status, json) =>
        HttpResponse(status = status, entity = HttpEntity(`application/ld+json`, printer.print(sortKeys(json))))
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
        HttpEntity(`application/ld+json`, printer.print(sortKeys(json)))
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

  private[routes] def onOf[A, Response](
      fMarshaller: MediaType.WithFixedCharset => Marshaller[A, Response]
  ): Marshaller[A, Response] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map(fMarshaller)
    Marshaller.oneOf(marshallers: _*)
  }
}
