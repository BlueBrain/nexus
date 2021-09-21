package ch.epfl.bluebrain.nexus.delta.sdk.error

import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet

import java.io.File

/**
  * Parent error type for plugin errors.
  *
  * @param reason
  *   a general reason for the error
  * @param details
  *   possible additional details that may be interesting to provide to the caller
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
    * @param reason
    *   a reason for the error
    */
  final case class PluginInitializationError(reason: String) extends PluginError(reason, None)

  /**
    * Multiple [[PluginDef]] classes found in the jar.
    *
    * @param file
    *   the jar file where multiple [[PluginDef]] s were found.
    * @param classes
    *   the classes implementing [[PluginDef]]
    */
  final case class MultiplePluginDefClassesFound(file: File, classes: Set[String])
      extends PluginError(
        s"Multiple plugin def classes found in ${file.getPath}, classes found: ${classes.mkString(",")}",
        None
      )

  /**
    * Plugin intialization error caused by a missing class.
    *
    * @param reason
    *   a descriptive reason for the error
    */
  final case class ClassNotFoundError(reason: String) extends PluginError(reason, None)

  /**
    * Plugin initialization phase failure caused by a set of plugins that could not be loaded.
    *
    * @param errors
    *   the non empty collection of failures
    */
  final case class PluginLoadErrors(errors: NonEmptySet[(File, PluginError)])
      extends PluginError(
        "Some plugins could not be loaded.",
        Some(
          errors.value
            .map { case (file, error) => s"\t - Plugin: $file\n\t - Cause: ${error.getMessage}" }
            .mkString("\n")
        )
      )
}
