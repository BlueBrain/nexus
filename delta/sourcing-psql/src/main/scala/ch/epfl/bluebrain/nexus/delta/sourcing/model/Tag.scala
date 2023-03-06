package ch.epfl.bluebrain.nexus.delta.sourcing.model

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLdCursor
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoderError.ParsingFailure
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label.IllegalLabelFormat
import doobie.Put
import doobie.util.Get
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

import scala.util.matching.Regex

sealed trait Tag extends Product with Serializable {

  def value: String

  override def toString: String = value

}

object Tag {

  sealed trait Latest extends Tag

  final case object Latest extends Latest {
    override val value: String = "latest"

    implicit val latestTagJsonLdDecoder: JsonLdDecoder[Latest] =
      (cursor: ExpandedJsonLdCursor) =>
        cursor.get[String].flatMap {
          case `value` => Right(Latest)
          case other   => Left(ParsingFailure(s"Tag '$other' does not match expected value 'latest'"))
        }

    implicit val latestTagDecoder: Decoder[Latest] =
      Decoder.decodeString.emap(str => if (str == "latest") Right(Latest) else Left("Expected 'latest' string"))
  }

  val latest: Tag = Latest

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

    implicit val userTagKeyEncoder: KeyEncoder[UserTag] = KeyEncoder.encodeKeyString.contramap(_.toString)
    implicit val userTagKeyDecoder: KeyDecoder[UserTag] = KeyDecoder.instance(UserTag(_).toOption)

    implicit final val userTagJsonLdDecoder: JsonLdDecoder[UserTag] =
      (cursor: ExpandedJsonLdCursor) =>
        cursor.get[String].flatMap { UserTag(_).leftMap { e => ParsingFailure(e.message) } }
  }

  implicit val tagGet: Get[Tag] = Get[String].map {
    case "latest" => Latest
    case s        => UserTag.unsafe(s)
  }

  implicit val tagPut: Put[Tag] = Put[String].contramap(_.value)

  implicit val tagEncoder: Encoder[Tag] =
    Encoder.encodeString.contramap(_.value)

  implicit val tagDecoder: Decoder[Tag] =
    Latest.latestTagDecoder or UserTag.userTagDecoder.map(identity[Tag])

  implicit val tagJsonLdDecoder: JsonLdDecoder[Tag] =
    Latest.latestTagJsonLdDecoder or UserTag.userTagJsonLdDecoder.covary[Tag]
}
