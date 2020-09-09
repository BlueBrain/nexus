package ch.epfl.bluebrain.nexus.delta.sdk.error

/**
  * Parent type for all expected (fully typed in IO operations) SDK errors.
  */
trait SDKError extends Exception { self =>
  override def fillInStackTrace(): Throwable = self
  def getMessage: String
}
