package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}

/**
  * Top level error type that represents illegal formatting of various tokens.
  *
  * @param reason  a general reason for the error
  * @param details possible additional details that may be interesting to provide to the caller
  */
sealed abstract class FormatError(reason: String, details: Option[String] = None) extends SDKError {
  final override def getMessage: String = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object FormatError {

  /**
    * Label formatting error, returned in cases where a Label could not be constructed from a String.
    *
    * @param details possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalLabelFormatError(details: Option[String] = None)
      extends FormatError(
        s"The provided string did not match the expected label format '${Label.regex.regex}'.",
        details
      )

  /**
    * Name formatting error, returned in cases where a Name could not be constructed from a String.
    *
    * @param details possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalNameFormatError(details: Option[String] = None)
      extends FormatError(
        s"The provided string did not match the expected name format '${Name.regex.regex}'.",
        details
      )

  /**
    * Permission formatting error, returned in cases where a Permission could not be constructed from a String.
    *
   * @param details possible additional details that may be interesting to provide to the caller
    */
  final case class IllegalPermissionFormatError(details: Option[String] = None)
      extends FormatError("The provided string did not match the expected permission format.", details)
}
