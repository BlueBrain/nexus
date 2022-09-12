package ch.epfl.bluebrain.nexus.delta.config

import pureconfig.error.ConfigReaderFailures

final case class AppConfigError(reason: String) extends Exception {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = reason
}

object AppConfigError {
  def apply(configReaderFailures: ConfigReaderFailures): AppConfigError =
    new AppConfigError(configReaderFailures.prettyPrint())
}
