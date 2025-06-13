package ai.senscience.nexus.delta.plugins.archive.model

import akka.NotUsed
import akka.http.scaladsl.model.{ContentType, MediaTypes}
import akka.http.scaladsl.server.Directive
import akka.http.scaladsl.server.Directives.extractRequest
import akka.stream.alpakka.file.ArchiveMetadata
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils

/**
  * Zip archive format
  *
  * @see
  *   https://en.wikipedia.org/wiki/ZIP_(file_format)#Limits for the limitations
  */
object Zip {
  type WriteFlow[Metadata] = Flow[(Metadata, Source[ByteString, ?]), ByteString, NotUsed]

  lazy val contentType: ContentType = MediaTypes.`application/zip`

  lazy val writeFlow: WriteFlow[ArchiveMetadata] = Archive.zip()

  lazy val ordering: Ordering[ArchiveMetadata] = Ordering.by(md => md.filePath)

  def metadata(filename: String): ArchiveMetadata = ArchiveMetadata.create(filename)

  def checkHeader: Directive[Tuple1[Boolean]] =
    extractRequest.map { req =>
      HeadersUtils.matches(req.headers, Zip.contentType.mediaType)
    }

}
