package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.util.UUID

class S3LocationGenerator(prefix: Path) {

  private def uuidToPath(uuid: UUID) =
    Uri.Path(uuid.toString.toLowerCase.takeWhile(_ != '-').mkString("/"))

  def file(project: ProjectRef, uuid: UUID, filename: String): Uri =
    Uri.Empty / prefix / project.organization.toString / project.project.toString / "files" / uuidToPath(
      uuid
    ) / filename

}
