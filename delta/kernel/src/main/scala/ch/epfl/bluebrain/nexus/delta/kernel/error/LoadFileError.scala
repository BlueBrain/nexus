package ch.epfl.bluebrain.nexus.delta.kernel.error

import java.nio.file.Path

/**
  * Top level error type that represents error loading external files.
  *
  * @param reason
  *   the reason of the error
  */
abstract class LoadFileError(reason: String) extends Exception { self =>
  override def fillInStackTrace(): Throwable = self
  final override def getMessage: String      = reason
}

object LoadFileError {

  final case class UnaccessibleFile(path: Path, throwable: Throwable)
      extends LoadFileError(s"File at path '$path' could not be loaded because of '${throwable.getMessage}'.")

  final case class InvalidJson(path: Path, details: String)
      extends LoadFileError(s"File at path '$path' does not contain the expected json input: '$details'.")

}
