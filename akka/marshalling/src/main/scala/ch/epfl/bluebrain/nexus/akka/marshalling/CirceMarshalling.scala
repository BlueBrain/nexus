package ch.epfl.bluebrain.nexus.akka.marshalling

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaType}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes.`application/ld+json`
import com.github.plokhotnyuk.jsoniter_scala.circe.JsoniterScalaCodec.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.circe.{Encoder, Json}

trait CirceMarshalling {

  private val mediaTypes: Seq[MediaType.WithFixedCharset] = List(`application/json`, `application/ld+json`)

  private val defaultWriterConfig: WriterConfig = WriterConfig.withPreferredBufSize(100 * 1024)

  /**
    * `Json` => HTTP entity
    */
  implicit final def jsonMarshaller: ToEntityMarshaller[Json] =
    Marshaller.oneOf(mediaTypes*) { mediaType =>
      Marshaller.withFixedContentType(ContentType(mediaType)) { json =>
        HttpEntity(
          mediaType,
          ByteString(writeToArray(json, defaultWriterConfig))
        )
      }
    }

  /**
    * `A` => HTTP entity
    */
  implicit final def marshaller[A: Encoder]: ToEntityMarshaller[A] =
    jsonMarshaller.compose(Encoder[A].apply)
}

object CirceMarshalling extends CirceMarshalling
