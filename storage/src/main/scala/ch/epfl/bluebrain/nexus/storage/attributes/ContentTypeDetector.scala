package ch.epfl.bluebrain.nexus.storage.attributes

import akka.http.scaladsl.model.{ContentType, MediaType, MediaTypes}
import akka.http.scaladsl.model.HttpCharsets.`UTF-8`
import akka.http.scaladsl.model.MediaTypes.{`application/octet-stream`, `application/x-tar`}
import ch.epfl.bluebrain.nexus.delta.kernel.http.MediaTypeDetectorConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.FileUtils

import java.nio.file.{Files, Path}
import scala.util.Try

final class ContentTypeDetector(config: MediaTypeDetectorConfig) {

  /**
    * Detects the media type of the provided path, based on the custom detector, the file system detector available for
    * a certain path or on the path extension. If the path is a directory, a application/x-tar content-type is returned
    *
    * @param path
    *   the path
    * @param isDir
    *   flag to decide whether or not the path is a directory
    */
  def apply(path: Path, isDir: Boolean = false): ContentType =
    if (isDir) {
      `application/x-tar`
    } else {
      val extension = FileUtils.extension(path.toFile.getName)

      val customDetector = extension.flatMap(config.find)

      def fileContentDetector =
        for {
          probed            <- Try(Files.probeContentType(path)).toOption
          rawContentType    <- Option.when(probed != null && probed.nonEmpty)(probed)
          parsedContentType <- MediaType.parse(rawContentType).toOption
        } yield parsedContentType

      def defaultAkkaDetector = extension.flatMap { e => Try(MediaTypes.forExtension(e)).toOption }

      val mediaType =
        customDetector.orElse(fileContentDetector).orElse(defaultAkkaDetector).getOrElse(`application/octet-stream`)
      ContentType(mediaType, () => `UTF-8`)
    }

}
