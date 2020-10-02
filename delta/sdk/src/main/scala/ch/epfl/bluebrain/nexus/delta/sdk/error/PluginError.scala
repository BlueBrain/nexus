package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.PluginInfo

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
  final case class DependencyNotFound(requiringPlugin: PluginInfo, dependencies: Set[PluginInfo])
      extends PluginError(
        s"Following dependencies required by plugin ${requiringPlugin.name.value}-${requiringPlugin.version} could not be found: ${dependencies
          .map { info =>
            s"${info.name.value}, version: ${info.version}"
          }
          .mkString(",")}.",
        None
      )

  final case class DependencyGraphCycle(graph: String)
      extends PluginError(
        s"There is a cycle in the following dependency graph: $graph",
        None
      )
}
