package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

import java.time.Instant

/**
  * Enumeration of archive states.
  */
sealed trait ArchiveState extends Product with Serializable

object ArchiveState {

  /**
    * Initial state of an archive.
    */
  final case object Initial extends ArchiveState

  /**
    * State of an existing archive.
    *
    * @param id        the archive identifier
    * @param project   the archive parent project
    * @param resources the collection of referenced resources
    * @param createdAt the instant when the archive was created
    * @param createdBy the subject that created the archive
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      resources: NonEmptySet[ArchiveReference],
      createdAt: Instant,
      createdBy: Subject
  ) extends ArchiveState
}
