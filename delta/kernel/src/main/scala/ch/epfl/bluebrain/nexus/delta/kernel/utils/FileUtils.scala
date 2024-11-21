package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.error.LoadFileError.{InvalidJson, UnaccessibleFile}
import io.circe.Decoder
import io.circe.parser.decode

import java.nio.file.{Files, Path}
import scala.util.Try

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

  def filenameWithoutExtension(filename: String): Option[String] = {
    val lastDotIndex = filename.lastIndexOf('.')
    Option.when(lastDotIndex > 0) {
      filename.substring(0, lastDotIndex)
    }
  }

  /**
    * Load the content of the given file as a string
    */
  def loadAsString(filePath: Path): IO[String] = IO.fromEither(
    Try(Files.readString(filePath)).toEither.leftMap(UnaccessibleFile(filePath, _))
  )

  /**
    * Load the content of the given file as json and try to decode it as an A
    * @param filePath
    *   the path of the target file
    */
  def loadJsonAs[A: Decoder](filePath: Path): IO[A] =
    for {
      content <- IO.fromEither(
                   Try(Files.readString(filePath)).toEither.leftMap(UnaccessibleFile(filePath, _))
                 )
      json    <- IO.fromEither(decode[A](content).leftMap { e => InvalidJson(filePath, e.getMessage) })
    } yield json

}
