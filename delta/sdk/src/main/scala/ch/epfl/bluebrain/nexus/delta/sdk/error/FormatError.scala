package ch.epfl.bluebrain.nexus.delta.sdk.error

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
      extends FormatError("The provided string did not match the expected label format.", details)

}
