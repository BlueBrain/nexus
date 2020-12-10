package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalPermissionFormatError
import io.circe.{Decoder, Encoder}

/**
  * Wraps a permission string that must begin with a letter, followed by at most 31
  * alphanumeric characters or symbols among '-', '_', ':', '\' and '/'.
  *
  * @param value a valid permission string
  */
final case class Permission private (value: String) extends AnyVal {
  override def toString: String = value
}

object Permission {

  private[sdk] val regex = """[a-zA-Z][\w-:\\/]{0,31}""".r

  /**
    * Attempts to construct a [[Permission]] that passes the ''regex''
    *
    * @param value the permission value
    */
  final def apply(value: String): Either[IllegalPermissionFormatError, Permission] =
    value match {
      case regex() => Right(unsafe(value))
      case _       => Left(IllegalPermissionFormatError())
    }

  /**
    * Constructs a [[Permission]] without validating it against the ''regex''
    *
    * @param value the permission value
    */
  final def unsafe(value: String): Permission =
    new Permission(value)

  implicit final val permissionEncoder: Encoder[Permission] =
    Encoder.encodeString.contramap(_.value)

  implicit final val permissionDecoder: Decoder[Permission] =
    Decoder.decodeString.emap(str => Permission(str).leftMap(_.getMessage))

  implicit final val permissionJsonLdDecoder: JsonLdDecoder[Permission] = _.getValue(apply(_).toOption)
}
