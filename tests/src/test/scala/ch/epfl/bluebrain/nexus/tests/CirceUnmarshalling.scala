package ch.epfl.bluebrain.nexus.tests

import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ContentTypeRange, MediaType}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.util.ByteString
import RdfMediaTypes.{`application/ld+json`, `application/sparql-results+json`}
import io.circe.{jawn, Decoder, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Unmarshallings that allow Akka Http to convert an [[HttpEntity]] to a type ''A'' using a [[Decoder]]
  * Partially ported from ''de.heikoseeberger.akkahttpcirce.CirceSupport''.
  */
trait CirceUnmarshalling {

  private val mediaTypes: Seq[MediaType.WithFixedCharset] =
    List(`application/json`, `application/ld+json`, `application/sparql-results+json`)

  private def unmarshallerContentTypes: Seq[ContentTypeRange] =
    mediaTypes.map(ContentTypeRange.apply)

  implicit final def fromByteStringUnmarshaller[A: Decoder]: Unmarshaller[ByteString, A] =
    Unmarshaller[ByteString, Json](_ => bs => Future.fromTry(jawn.parseByteBuffer(bs.asByteBuffer).toTry))
      .map(Decoder[A].decodeJson)
      .map(_.fold(throw _, identity))

  /**
    * HTTP entity => `Json`
    *
    * @return unmarshaller for `Json`
    */
  implicit final val jsonUnmarshaller: FromEntityUnmarshaller[Json] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(unmarshallerContentTypes: _*)
      .map {
        case ByteString.empty => throw Unmarshaller.NoContentException
        case data             => jawn.parseByteBuffer(data.asByteBuffer).fold(throw _, identity)
      }

  /**
    * HTTP entity => `Json` => `A`
    *
    * @return unmarshaller for `A`
    */
  implicit final def decoderUnmarshaller[A: Decoder]: FromEntityUnmarshaller[A] =
    jsonUnmarshaller
      .map(Decoder[A].decodeJson)
      .map(_.fold(throw _, identity))
}

object CirceUnmarshalling extends CirceUnmarshalling
