package ch.epfl.bluebrain.nexus.commons.http.directives

import akka.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import akka.http.scaladsl.model._
import ch.epfl.bluebrain.nexus.commons.circe.ContextUri
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes
import io.circe.{Encoder, Printer}

/**
  * Directive to marshall StatusFrom instances into HTTP responses.
  */
object ErrorDirectives {

  /**
    * Implicitly derives a generic [[akka.http.scaladsl.marshalling.ToResponseMarshaller]] based on
    * implicitly available [[StatusFrom]] and [[io.circe.Encoder]] instances, and a [[ContextUri]], that provides
    * a JSON-LD response from an entity of type ''A''.
    *
    * @tparam A the generic type for which the marshaller is derived
    * @param statusFrom the StatusFrom instance mapping the entity to an HTTP status
    * @param encoder the Circe encoder instance to convert the entity into JSON
    * @param context the context URI to be injected into the JSON-LD response body
    * @param orderedKeys the order in which the keys in the JSON-LD are going to be sorted
    * @param printer a pretty-printer for JSON values
    * @return a ''ToResponseMarshaller'' that will generate an appropriate JSON-LD response
    */
  final implicit def jsonLdMarshallerFromStatusAndEncoder[A](
      implicit
      statusFrom: StatusFrom[A],
      encoder: Encoder[A],
      context: ContextUri,
      orderedKeys: OrderedKeys = OrderedKeys(List("@context", "code", "message", "details", "")),
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true)
  ): ToResponseMarshaller[A] =
    Marshaller.withFixedContentType(RdfMediaTypes.`application/ld+json`) { value =>
      HttpResponse(
        status = statusFrom(value),
        entity = HttpEntity(
          RdfMediaTypes.`application/ld+json`,
          printer.print(encoder.mapJson(_.addContext(context)).apply(value).sortKeys)
        )
      )
    }
}
