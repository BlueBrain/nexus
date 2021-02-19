package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

import java.nio.file.Path

/**
  * Enumeration of archive rejection types.
  *
  * @param reason a descriptive message as to why the rejection occurred
  */
sealed abstract class ArchiveRejection(val reason: String) extends Product with Serializable

object ArchiveRejection {

  /**
    * Rejection returned when attempting to create an archive with an id that already exists.
    *
    * @param id      the archive id
    * @param project the project it belongs to
    */
  final case class ArchiveAlreadyExists(id: Iri, project: ProjectRef)
      extends ArchiveRejection(s"Archive '$id' already exists in project '$project'.")

  /**
    * Rejection returned when there's at least a path collision in the archive.
    *
    * @param paths the offending paths
    */
  final case class DuplicateResourcePath(paths: Set[Path])
      extends ArchiveRejection(s"The paths '${paths.mkString(", ")}' have been used multiple times in the archive.")

  /**
    * Rejection returned when the target path of a resource is not absolute.
    *
    * @param paths the offending paths
    */
  final case class PathIsNotAbsolute(paths: Set[Path])
      extends ArchiveRejection(
        s"The paths '${paths.mkString(", ")}' are not absolute. Only absolute paths are allowed."
      )
}
