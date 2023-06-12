package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import akka.NotUsed
import akka.http.scaladsl.model.{ContentType, HttpRequest, MediaTypes}
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.alpakka.file.{ArchiveMetadata, TarArchiveMetadata}
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveFormat.WriteFlow
import ch.epfl.bluebrain.nexus.delta.sdk.utils.HeadersUtils

/**
  * Available format to download the archive
  */
sealed trait ArchiveFormat[Metadata] extends Product with Serializable {

  /**
    * Content type
    */
  def contentType: ContentType

  /**
    * File extension
    */
  def fileExtension: String

  /**
    * How to build the metadata for the archive entry
    */
  def metadata(filename: String, size: Long): Metadata

  /**
    * Ordering for the archive entries
    */
  def ordering: Ordering[Metadata] = Ordering.by(filePath)

  /**
    * How to extract the file path from the archive metadata
    */
  def filePath(metadata: Metadata): String

  /**
    * Flow to create an archive
    */
  def writeFlow: WriteFlow[Metadata]
}

object ArchiveFormat {

  type WriteFlow[Metadata] = Flow[(Metadata, Source[ByteString, _]), ByteString, NotUsed]

  /**
    * Tar format
    * @see
    *   https://en.wikipedia.org/wiki/Tar_(computing)#Limitations for the limitations
    */
  final case object Tar extends ArchiveFormat[TarArchiveMetadata] {
    override def contentType: ContentType = MediaTypes.`application/x-tar`

    override def fileExtension: String = "tar"

    override def metadata(filename: String, size: Long): TarArchiveMetadata =
      TarArchiveMetadata.create(filename, size)

    override def filePath(metadata: TarArchiveMetadata): String = metadata.filePath

    override def writeFlow: WriteFlow[TarArchiveMetadata] = Archive.tar()
  }

  /**
    * Zip format
    *
    * @see
    *   https://en.wikipedia.org/wiki/ZIP_(file_format)#Limits for the limitations
    */
  final case object Zip extends ArchiveFormat[ArchiveMetadata] {
    override def contentType: ContentType = MediaTypes.`application/zip`

    override def fileExtension: String = "zip"

    override def metadata(filename: String, size: Long): ArchiveMetadata =
      ArchiveMetadata.create(filename)

    override def filePath(metadata: ArchiveMetadata): String = metadata.filePath

    override def writeFlow: WriteFlow[ArchiveMetadata] = Archive.zip()
  }

  private val availableFormats = List(Tar, Zip)

  def apply(req: HttpRequest): Option[ArchiveFormat[_]] = availableFormats.find { format =>
    HeadersUtils.matches(req.headers, format.contentType.mediaType)
  }
}
