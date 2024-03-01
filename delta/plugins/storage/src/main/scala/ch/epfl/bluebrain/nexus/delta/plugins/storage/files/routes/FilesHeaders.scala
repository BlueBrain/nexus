package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives.optionalHeaderValuePF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCustomMetadata
import io.circe.parser

import scala.util.Try

object FilesHeaders {

  final class NexusFileMetadataHeader(value: String) extends ModeledCustomHeader[NexusFileMetadataHeader] {
    override def renderInRequests  = true
    override def renderInResponses = true
    override val companion         = NexusFileMetadataHeader

    override def value: String = value
  }

  object NexusFileMetadataHeader extends ModeledCustomHeaderCompanion[NexusFileMetadataHeader] {
    override val name                 = "x-nxs-file-metadata"
    override def parse(value: String) = Try(new NexusFileMetadataHeader(value))
  }

  // TODO: handle the rejection
  def extractFileMetadata: Directive1[Option[FileCustomMetadata]] =
    optionalHeaderValuePF { case NexusFileMetadataHeader(value) =>
      parser.parse(value).flatMap(_.as[FileCustomMetadata]).getOrElse(FileCustomMetadata.empty)
    }

}
