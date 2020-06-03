package ch.epfl.bluebrain.nexus.iam.types

import cats.Show
import io.circe._

/**
  * Wraps a permission string that must begin with a letter, followed by at most 31
  * alphanumeric characters or symbols among '-', '_', ':', '\' and '/'.
  *
  * @param value a valid permission string
  */
final case class Permission private (value: String)

object Permission {

  private val valid = """[a-zA-Z][\w-:\\/]{0,31}""".r

  /**
    * Attempts to construct a [[Permission]] that passes the ''regex''
    *
    * @param value the permission value
    */
  final def apply(value: String): Option[Permission] = value match {
    case valid() => Some(unsafe(value))
    case _       => None
  }

  /**
    * Constructs a [[Permission]] without validating it against the ''regex''
    *
    * @param value the permission value
    */
  final def unsafe(value: String): Permission =
    new Permission(value)

  implicit val permShow: Show[Permission] = Show.show { case Permission(value) => value }

  implicit val permKeyEncoder: KeyEncoder[Permission] = KeyEncoder.encodeKeyString.contramap(_.value)

  implicit val permKeyDecoder: KeyDecoder[Permission] = KeyDecoder.instance(apply)

  implicit val permEncoder: Encoder[Permission] = Encoder.encodeString.contramap[Permission](_.value)

  implicit val permDecoder: Decoder[Permission] =
    Decoder.decodeString.emap(apply(_).toRight("Illegal permission format"))
}
