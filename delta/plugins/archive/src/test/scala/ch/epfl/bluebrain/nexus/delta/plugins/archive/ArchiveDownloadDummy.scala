package ch.epfl.bluebrain.nexus.delta.plugins.archive
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.{ArchiveRejection, ArchiveValue}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.IO

/**
  * [[ArchiveDownload]] dummy implementation that returns an empty source.
  */
class ArchiveDownloadDummy extends ArchiveDownload {

  override def apply(
      value: ArchiveValue,
      project: ProjectRef,
      ignoreNotFound: Boolean
  )(implicit caller: Caller): IO[ArchiveRejection, Source[ByteString, Any]] = IO.pure(Source.empty)

}

object ArchiveDownloadDummy {

  /**
    * Factory method for [[ArchiveDownloadDummy]].
    */
  def apply(): ArchiveDownloadDummy =
    new ArchiveDownloadDummy
}
