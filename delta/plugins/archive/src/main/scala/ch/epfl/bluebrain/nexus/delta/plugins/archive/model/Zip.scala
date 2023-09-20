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

  def apply(req: HttpRequest): Option[Zip.type] =
    if (HeadersUtils.matches(req.headers, Zip.contentType.mediaType)) Some(Zip) else None

  def contentType: ContentType = MediaTypes.`application/zip`

  def fileExtension: String = "zip"

  def metadata(filename: String): ArchiveMetadata =
    ArchiveMetadata.create(filename)

  def writeFlow: WriteFlow[ArchiveMetadata] = Archive.zip()

  def ordering: Ordering[ArchiveMetadata] = Ordering.by(md => md.filePath)
}
