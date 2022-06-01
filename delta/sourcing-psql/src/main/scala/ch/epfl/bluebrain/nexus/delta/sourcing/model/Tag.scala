package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label.IllegalLabelFormat
import doobie.Put
import doobie.util.Get
import io.circe.{Decoder, Encoder}

import scala.util.matching.Regex

sealed trait Tag extends Product with Serializable {

  def value: String

  override def toString: String = value

}

object Tag {

  sealed trait Latest extends Tag

  final case object Latest extends Latest {
    override def value: String = "latest"
  }

  final case class UserTag private (value: String) extends Tag

  object UserTag {

    private[sourcing] val regex: Regex = "[\\p{ASCII}]{1,64}".r

    def unsafe(value: String) = new UserTag(value)

    def apply(value: String): Either[IllegalLabelFormat, UserTag] =
      Either.cond(value != "latest", value, IllegalLabelFormat("'latest' is a reserved tag")).flatMap {
        case value @ regex() => Right(new UserTag(value))
        case value           => Left(IllegalLabelFormat(value))
      }

    implicit final val userTagEncoder: Encoder[UserTag] =
      Encoder.encodeString.contramap(_.value)

    implicit final val userTagDecoder: Decoder[UserTag] =
      Decoder.decodeString.emap(str => UserTag(str).leftMap(_.message))

    implicit final val userTagJsonLdDecoder: JsonLdDecoder[UserTag] =
      (cursor: ExpandedJsonLdCursor) =>
        cursor.get[String].flatMap { UserTag(_).leftMap { e => ParsingFailure(e.message) } }
  }

  implicit val tagGet: Get[Tag] = Get[String].map {
    case "latest" => Latest
    case s        => UserTag.unsafe(s)
  }

  implicit val tagPut: Put[Tag] = Put[String].contramap(_.value)
}
