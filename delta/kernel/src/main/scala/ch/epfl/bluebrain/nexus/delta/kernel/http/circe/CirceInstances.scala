package ch.epfl.bluebrain.nexus.delta.kernel.http.circe

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.http.circe.CirceInstances.{defaultParseExceptionMessage, jsonDecodeErrorHelper}
import com.github.plokhotnyuk.jsoniter_scala.circe.JsoniterScalaCodec.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, DecodingFailure, Encoder, Json}
import org.http4s.headers.`Content-Type`
import org.http4s.{DecodeFailure, DecodeResult, EntityDecoder, EntityEncoder, InvalidMessageBodyFailure, MalformedMessageBodyFailure, MediaRange, MediaType, Uri}

trait CirceInstances {

  private val defaultWriterConfig: WriterConfig = WriterConfig.withPreferredBufSize(100 * 1024)

  private[circe] lazy val defaultJsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure = {
    (json, failures) =>
      jsonDecodeErrorHelper(json, _.toString, failures)
  }

  protected def jsonDecodeError: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure =
    defaultJsonDecodeError

  def jsonEncoderOf[A: Encoder]: EntityEncoder[IO, A] =
    EntityEncoder
      .byteArrayEncoder[IO]
      .contramap[A] { value => writeToArray(value.asJson, defaultWriterConfig) }
      .withContentType(`Content-Type`(MediaType.application.json))

  def jsonOf[A: Decoder]: EntityDecoder[IO, A] =
    jsonOfWithMedia(MediaType.application.json)

  implicit val jsonEncoder: EntityEncoder[IO, Json] = jsonEncoderOf

  def jsonOfWithMedia[A: Decoder](r1: MediaRange, rs: MediaRange*): EntityDecoder[IO, A] =
    jsonOfWithMediaHelper[A](r1, jsonDecodeError, rs*)

  private def jsonOfWithMediaHelper[A: Decoder](
      r1: MediaRange,
      decodeErrorHandler: (Json, NonEmptyList[DecodingFailure]) => DecodeFailure,
      rs: MediaRange*
  ): EntityDecoder[IO, A] =
    jsonDecoder(r1, rs*).flatMapR { json =>
      json
        .as[A]
        .fold(
          failure => DecodeResult.failureT(decodeErrorHandler(json, NonEmptyList.one(failure))),
          DecodeResult.successT(_)
        )
    }

  def jsonDecoder(r1: MediaRange, rs: MediaRange*): EntityDecoder[IO, Json] =
    EntityDecoder.decodeBy(r1, rs*) { msg =>
      DecodeResult {
        msg.body.compile
          .to(Array)
          .map { bytes =>
            readFromArray[Json](bytes)
          }
          .attempt
          .map { _.leftMap(defaultParseExceptionMessage) }
      }
    }

  implicit val jsonEntityDecoder: EntityDecoder[IO, Json] = jsonDecoder(MediaType.application.json)

  implicit val encodeUri: Encoder[Uri] =
    Encoder.encodeString.contramap[Uri](_.toString)

  implicit val decodeUri: Decoder[Uri] =
    Decoder.decodeString.emap { str =>
      Uri.fromString(str).leftMap(_ => "Uri")
    }
}

object CirceInstances {
  private def jsonDecodeErrorHelper(
      json: Json,
      jsonToString: Json => String,
      failures: NonEmptyList[DecodingFailure]
  ): DecodeFailure = {

    val str: String = jsonToString(json)

    InvalidMessageBodyFailure(
      s"Could not decode JSON: $str",
      if (failures.tail.isEmpty) Some(failures.head) else Some(DecodingFailures(failures))
    )
  }

  private def defaultParseExceptionMessage: Throwable => DecodeFailure =
    pe => MalformedMessageBodyFailure("Invalid JSON", Some(pe))
}
