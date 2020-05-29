package ch.epfl.bluebrain.nexus.commons.http

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentTypeRange, HttpEntity}
import ch.epfl.bluebrain.nexus.commons.circe.ContextUri
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport.OrderedKeys
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.{Encoder, Json, Printer}

import scala.collection.immutable.Seq

/**
  * Json-LD specific akka http circe support.
  *
  * It uses [[ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes.`application/ld+json`]] as the default content
  * type for encoding json trees into http request payloads.
  */
trait JsonLdCirceSupport extends FailFastCirceSupport {

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, RdfMediaTypes.`application/ld+json`, RdfMediaTypes.`application/sparql-results+json`)

  /**
    * `A` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def marshallerHttp[A](
      implicit
      context: ContextUri,
      encoder: Encoder[A],
      printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      keys: OrderedKeys = OrderedKeys()
  ): ToEntityMarshaller[A] =
    jsonLdMarshaller.compose(encoder.mapJson(_.addContext(context)).apply)

  /**
    * `Json` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  final implicit def jsonLdMarshaller(
      implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      keys: OrderedKeys = OrderedKeys()
  ): ToEntityMarshaller[Json] =
    Marshaller.withFixedContentType(RdfMediaTypes.`application/ld+json`) { json =>
      HttpEntity(RdfMediaTypes.`application/ld+json`, printer.print(json.sortKeys))
    }
}

object JsonLdCirceSupport extends JsonLdCirceSupport {

  /**
    * Data type which holds the ordering for the JSON-LD keys.
    *
    * @param keys list of strings which defines the ordering for the JSON-LD keys
    */
  final case class OrderedKeys(keys: List[String]) {
    lazy val withPosition: Map[String, Int] = keys.zipWithIndex.toMap
  }
  object OrderedKeys {

    /**
      * Construct an empty [[OrderedKeys]]
      */
    final def apply(): OrderedKeys = new OrderedKeys(List(""))
  }
}
