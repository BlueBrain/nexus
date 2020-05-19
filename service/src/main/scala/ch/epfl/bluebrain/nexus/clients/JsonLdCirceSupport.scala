package ch.epfl.bluebrain.nexus.clients

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentTypeRange, HttpEntity, MessageEntity}
import io.circe.{Encoder, Json, Printer}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import ch.epfl.bluebrain.nexus.syntax.all._

import scala.collection.immutable.Seq
import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport.OrderedKeys
import ch.epfl.bluebrain.nexus.rdf.RdfMediaTypes._
import io.circe.syntax._

/**
  * Json-LD akka http circe support.
  *
  * It uses ''application/ld+json'' as the default content type for encoding json trees into http request payloads.
  */
trait JsonLdCirceSupport extends FailFastCirceSupport {

  override def unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`)

  /**
    * `A` => HTTP entity
    *
    * @tparam A type to encode
    * @return marshaller for any `A` value
    */
  implicit final def marshallerHttp[A: Encoder](
      implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      orderedKeys: OrderedKeys = OrderedKeys.alphabetically
  ): ToEntityMarshaller[A] =
    jsonLdMarshaller.compose(_.asJson)

  /**
    * `Json` => HTTP entity
    *
    * @return marshaller for JSON-LD value
    */
  implicit final def jsonLdMarshaller(
      implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true),
      orderedKeys: OrderedKeys = OrderedKeys.alphabetically
  ): ToEntityMarshaller[Json] = {
    val marshallers = Seq(`application/ld+json`, `application/json`).map(contentType =>
      Marshaller.withFixedContentType[Json, MessageEntity](contentType) { json =>
        HttpEntity(`application/ld+json`, printer.print(json.sortKeys))
      }
    )
    Marshaller.oneOf(marshallers: _*)
  }
}

object JsonLdCirceSupport extends JsonLdCirceSupport {

  /**
    * Data type which holds the ordering for the JSON-LD keys.
    * The keys not passed will be sorted alphabetically.
    *
    * @param top sequence of keys which defines the ordering for the JSON-LD top keys
    * @param bottom list of strings which defines the ordering for the JSON-LD keys bottom keys
    */
  final case class OrderedKeys(top: Seq[String], bottom: Seq[String]) {
    lazy val withPosition: Map[String, Int] = ((top :+ "") ++ bottom).zipWithIndex.toMap
  }
  object OrderedKeys {
    final val alphabetically: OrderedKeys = OrderedKeys(Seq.empty, Seq.empty)
  }
}
