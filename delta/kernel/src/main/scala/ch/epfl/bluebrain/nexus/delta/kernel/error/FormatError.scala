package ch.epfl.bluebrain.nexus.delta.kernel.error

/**
  * Top level error type that represents illegal formatting of various tokens.
  *
  * @param reason
  *   a general reason for the error
  * @param details
  *   possible additional details that may be interesting to provide to the caller
  */
abstract class FormatError(reason: String, details: Option[String] = None) extends Exception { self =>
  override def fillInStackTrace(): Throwable = self
  final override def getMessage: String      = details.fold(reason)(d => s"$reason\nDetails: $d")
}
