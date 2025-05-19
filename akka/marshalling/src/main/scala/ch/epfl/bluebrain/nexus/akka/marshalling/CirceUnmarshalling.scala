package ch.epfl.bluebrain.nexus.akka.marshalling

import akka.http.scaladsl.model.ContentTypeRange
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes.*
import com.github.plokhotnyuk.jsoniter_scala.circe.JsoniterScalaCodec.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.circe.{Decoder, Json}

import scala.concurrent.Future

/**
  * Unmarshallings that allow Akka Http to convert an [[HttpEntity]] to a type ''A'' using a [[Decoder]] Partially
  * ported from ''de.heikoseeberger.akkahttpcirce.CirceSupport''.
  */
trait CirceUnmarshalling {

  private val unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`).map(ContentTypeRange.apply)

  /**
    * HTTP entity => `Json`
    */
  implicit final val jsonUnmarshaller: FromEntityUnmarshaller[Json] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(unmarshallerContentTypes*)
      .map {
        case ByteString.empty => throw Unmarshaller.NoContentException
        case data             => readFromArray[Json](data.toArray)
      }

  /**
    * HTTP entity => `Json` => `A`
    */
  implicit final def decoderUnmarshaller[A: Decoder]: FromEntityUnmarshaller[A] =
    jsonUnmarshaller.map(Decoder[A].decodeJson).map(_.fold(throw _, identity))

  /**
    * ByteString => `Json`
    */
  implicit final def fromByteStringUnmarshaller[A: Decoder]: Unmarshaller[ByteString, A] =
    Unmarshaller[ByteString, Json](ec => bs => Future(readFromByteBuffer(bs.asByteBuffer))(ec))
      .map(Decoder[A].decodeJson)
      .map(_.fold(throw _, identity))
}

object CirceUnmarshalling extends CirceUnmarshalling
