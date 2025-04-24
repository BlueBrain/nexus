package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import org.http4s.Uri
import org.http4s.Uri.Path

import java.util.UUID

class S3LocationGenerator(prefix: Path) {

  private def uuidToPath(uuid: UUID) =
    Uri.Path.unsafeFromString(uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/"))

  def file(project: ProjectRef, uuid: UUID, filename: String): Uri = {
    val org         = project.organization.toString
    val proj        = project.project.toString
    val projectRoot = Uri.Path.empty.concat(prefix) / org / proj / "files"
    val path        = projectRoot.concat(uuidToPath(uuid)) / filename
    Uri(path = path)
  }

}
