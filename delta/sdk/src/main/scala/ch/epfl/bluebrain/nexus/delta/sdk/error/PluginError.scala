package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.sdk.model.Name

/**
  * Parent error type for plugin errors.
  *
  * @param reason  a general reason for the error
  * @param details possible additional details that may be interesting to provide to the caller
  */
sealed abstract class PluginError(reason: String, details: Option[String]) extends SDKError {
  final override def getMessage: String = details.fold(reason)(d => s"$reason\nDetails: $d")
}
object PluginError {

  /**
    * Requested plugin dependency couldn't be found.
    *
   * @param dependencies  dependencies that couldn't be found.
    */
  final case class DependencyNotFound(dependencies: Set[(Name, String)])
      extends PluginError(
        s"Following dependencies could not be found: ${dependencies
          .map {
            case (name, version) => s"${name.value}, version: $version"
          }
          .mkString(",")}.",
        None
      )
}
