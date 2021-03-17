package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ApiMappings, ProjectBase, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
  * Enumeration of archive states.
  */
sealed trait ArchiveState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource]
}

object ArchiveState {

  /**
    * Initial state of an archive.
    */
  final case object Initial extends ArchiveState {
    override def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource] =
      None
  }

  /**
    * State of an existing archive.
    *
    * @param id        the archive identifier
    * @param project   the archive parent project
    * @param value     the archive value
    * @param createdAt the instant when the archive was created
    * @param createdBy the subject that created the archive
    */
  final case class Current(
      id: Iri,
      project: ProjectRef,
      value: ArchiveValue,
      createdAt: Instant,
      createdBy: Subject
  ) extends ArchiveState {
    override def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): Option[ArchiveResource] =
      Some(
        ResourceF(
          id = id,
          uris = ResourceUris.ephemeral("archives", project, id)(mappings, base),
          rev = 1L,
          types = Set(tpe),
          deprecated = false,
          createdAt = createdAt,
          createdBy = createdBy,
          updatedAt = createdAt,
          updatedBy = createdBy,
          schema = schema,
          value = Archive(id, project, value.resources, ttl.toSeconds)
        )
      )
  }
}
