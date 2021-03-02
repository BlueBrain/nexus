package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import io.circe.{Decoder, Encoder}

import java.nio.file.{Path, Paths}
import scala.util.{Failure, Success, Try}

/**
  * A path that is guaranteed to be absolute.
  *
  * @param value the path value
  */
final case class AbsolutePath private (value: Path) extends AnyVal {
  override def toString: String = value.toString
}

object AbsolutePath {

  /**
    * Safely constructs an absolute path.
    *
    * @param string the string representation of the path
    */
  final def apply(string: String): Either[String, AbsolutePath] =
    Try(Paths.get(string)) match {
      case Failure(_)     => Left(s"Unable to parse the provided string '$string' as a valid Path.")
      case Success(value) => apply(value)
    }

  /**
    * Safely constructs an absolute path.
    *
    * @param path the unsafe path value
    */
  final def apply(path: Path): Either[String, AbsolutePath] =
    if (path.isAbsolute) Right(new AbsolutePath(path))
    else Left(s"The provided path '$path' is not absolute.")

  implicit final val absolutePathJsonEncoder: Encoder[AbsolutePath] =
    Encoder.encodeString.contramap(_.toString)

  implicit final val absolutePathJsonDecoder: Decoder[AbsolutePath] =
    Decoder.decodeString.emap(AbsolutePath.apply)

  implicit final val absolutePathJsonLdDecoder: JsonLdDecoder[AbsolutePath] =
    JsonLdDecoder.stringJsonLdDecoder.andThen { (cursor, str) =>
      Try(Paths.get(str)) match {
        case Failure(_)     => Left(ParsingFailure("AbsolutePath", str, cursor.history))
        case Success(value) => apply(value).leftMap(err => ParsingFailure(err))
      }
    }
}
