package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import akka.NotUsed
import akka.http.scaladsl.model.{ContentType, HttpRequest, MediaTypes}
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.alpakka.file.ArchiveMetadata
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
  type WriteFlow[Metadata] = Flow[(Metadata, Source[ByteString, _]), ByteString, NotUsed]

  lazy val contentType: ContentType = MediaTypes.`application/zip`

  lazy val writeFlow: WriteFlow[ArchiveMetadata] = Archive.zip()

  lazy val ordering: Ordering[ArchiveMetadata] = Ordering.by(md => md.filePath)

  def metadata(filename: String): ArchiveMetadata = ArchiveMetadata.create(filename)

  def checkHeader(req: HttpRequest): Boolean = HeadersUtils.matches(req.headers, Zip.contentType.mediaType)
}
