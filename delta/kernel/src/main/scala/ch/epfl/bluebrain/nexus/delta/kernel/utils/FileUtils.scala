package ch.epfl.bluebrain.nexus.delta.kernel.utils

object FileUtils {

  /**
    * Extracts the extension from the given filename
    */
  def extension(filename: String): Option[String] = {
    val lastDotIndex = filename.lastIndexOf('.')
    Option.when(lastDotIndex >= 0) {
      filename.substring(lastDotIndex + 1)
    }
  }

}
