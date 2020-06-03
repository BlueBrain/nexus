package ch.epfl.bluebrain.nexus.iam

/**
  * An exception to be used while testing to recognize expected error entries in the log and avoid unnecessarily long
  * stacktraces.
  */
object ExpectedException extends Exception {
  override def fillInStackTrace(): Throwable = this
}
