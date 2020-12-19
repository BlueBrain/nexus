package ch.epfl.bluebrain.nexus.delta.sdk.circe

import akka.http.scaladsl.model.ContentTypeRange
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import io.circe.{jawn, Decoder, Json}

import scala.collection.immutable.Seq
import scala.concurrent.Future

/**
  * Unmarshallings that allow Akka Http to convert an [[HttpEntity]] to a type ''A'' using a [[Decoder]]
  * Partially ported from ''de.heikoseeberger.akkahttpcirce.CirceSupport''.
  */
trait CirceUnmarshalling {

  private val unmarshallerContentTypes: Seq[ContentTypeRange] =
    List(`application/json`, `application/ld+json`).map(ContentTypeRange.apply)

  /**
    * HTTP entity => `Json`
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
    */
  implicit final def decoderUnmarshaller[A: Decoder]: FromEntityUnmarshaller[A] =
    jsonUnmarshaller.map(Decoder[A].decodeJson).map(_.fold(throw _, identity))

  /**
    * ByteString => `Json`
    */
  implicit final def fromByteStringUnmarshaller[A: Decoder]: Unmarshaller[ByteString, A] =
    Unmarshaller[ByteString, Json](_ => bs => Future.fromTry(jawn.parseByteBuffer(bs.asByteBuffer).toTry))
      .map(Decoder[A].decodeJson)
      .map(_.fold(throw _, identity))
}

object CirceUnmarshalling extends CirceUnmarshalling
