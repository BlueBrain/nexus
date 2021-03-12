package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

import java.time.Instant

/**
  * The event that asserts the creation of an archive.
  *
  * @param id      the identifier of the archive
  * @param project the parent project
  * @param value   the archive value
  * @param instant the instant when this event was emitted
  * @param subject the subject that performed the action that resulted in emitting this event
  */
final case class ArchiveCreated(
    id: Iri,
    project: ProjectRef,
    value: ArchiveValue,
    instant: Instant,
    subject: Subject
) extends ProjectScopedEvent {
  override val rev: Long = 1L
}
