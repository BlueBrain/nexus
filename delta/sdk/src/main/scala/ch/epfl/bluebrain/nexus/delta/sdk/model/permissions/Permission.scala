package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalPermissionFormatError

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
  final def apply(value: String): Either[IllegalPermissionFormatError, Permission] =
    value match {
      case valid() => Right(unsafe(value))
      case _       => Left(IllegalPermissionFormatError())
    }

  /**
    * Constructs a [[Permission]] without validating it against the ''regex''
    *
    * @param value the permission value
    */
  final def unsafe(value: String): Permission =
    new Permission(value)
}
