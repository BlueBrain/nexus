package ch.epfl.bluebrain.nexus.delta.sdk.error

import java.io.File

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
    * Plugin initialization error.
    *
    * Signals unexpected plugin initialization error.
    *
    * @param reason  a reason for the error
    */
  final case class PluginInitializationError(reason: String) extends PluginError(reason, None)

  /**
    * Multiple [[PluginDef]] classes found in the jar.
    *
    * @param file     the jar file where multiple [[PluginDef]]s were found.
    * @param classes  the classes implementing [[PluginDef]]
    */
  final case class MultiplePluginDefClassesFound(file: File, classes: Set[String])
      extends PluginError(
        s"Multiple plugin def classes found in ${file.getPath}, classes found: ${classes.mkString(",")}",
        None
      )
}
