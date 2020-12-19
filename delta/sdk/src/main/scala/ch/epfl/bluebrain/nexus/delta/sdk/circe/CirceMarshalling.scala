package ch.epfl.bluebrain.nexus.delta.sdk.circe

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaType}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import io.circe.{Encoder, Json, Printer}

trait CirceMarshalling {

  private val mediaTypes: Seq[MediaType.WithFixedCharset] = List(`application/json`, `application/ld+json`)

  /**
    * `Json` => HTTP entity
    */
  implicit final def jsonMarshaller(implicit printer: Printer = Printer.noSpaces): ToEntityMarshaller[Json] =
    Marshaller.oneOf(mediaTypes: _*) { mediaType =>
      Marshaller.withFixedContentType(ContentType(mediaType)) { json =>
        HttpEntity(
          mediaType,
          ByteString(printer.printToByteBuffer(json, mediaType.charset.nioCharset()))
        )
      }
    }

  /**
    * `A` => HTTP entity
    */
  implicit final def marshaller[A: Encoder](implicit printer: Printer = Printer.noSpaces): ToEntityMarshaller[A] =
    jsonMarshaller(printer).compose(Encoder[A].apply)
}

object CirceMarshalling extends CirceMarshalling
