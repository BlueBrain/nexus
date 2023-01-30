package ch.epfl.bluebrain.nexus.delta.config

import pureconfig.error.ConfigReaderFailures

/**
  * Error representing issues loading the application configuration.
  * @param reason
  *   the reason why the application configuration could not be loaded
  */
final case class AppConfigError(reason: String) extends Exception {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = reason
}

object AppConfigError {

  /**
    * Constructs an [[AppConfigError]] from a set of [[ConfigReaderFailures]].
    * @param configReaderFailures
    *   the underlying config read failures
    */
  def apply(configReaderFailures: ConfigReaderFailures): AppConfigError =
    new AppConfigError(configReaderFailures.prettyPrint())
}
