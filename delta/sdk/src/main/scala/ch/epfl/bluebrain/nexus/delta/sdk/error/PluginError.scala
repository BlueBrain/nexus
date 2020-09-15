package ch.epfl.bluebrain.nexus.delta.sdk.error

trait PluginError extends SDKError

/**
  * Parent error type for plugin errors.
  */
object PluginError {

  /**
    * Requested plugin dependency couldn't be found.
    */
  final case object DependencyNotFound extends PluginError
}
