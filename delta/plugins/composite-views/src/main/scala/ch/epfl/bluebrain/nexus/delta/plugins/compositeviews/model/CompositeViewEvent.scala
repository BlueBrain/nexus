package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.TagLabel
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * Composite view event enumeration.
  */
sealed trait CompositeViewEvent extends ProjectScopedEvent {

  /**
    * @return the view identifier
    */
  def id: Iri

  /**
    * @return the project to which the view belongs
    */
  def project: ProjectRef

  /**
    * @return the view unique identifier
    */
  def uuid: UUID
}

object CompositeViewEvent {

  /**
    * Evidence of a view creation.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param uuid    the view unique identifier
    * @param value   the view value
    * @param source  the original json value provided by the caller
    * @param rev     the revision that the event generates
    * @param instant the instant when the event was emitted
    * @param subject the subject that created the view
    */
  final case class CompositeViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: CompositeViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of a view update.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param value   the view value
    * @param source  the original json value provided by the caller
    * @param rev     the revision that the event generates
    * @param instant the instant when the event was emitted
    * @param subject the subject that updated the view
    */
  final case class CompositeViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: CompositeViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of tagging a view.
    *
    * @param id        the view identifier
    * @param project   the view parent project
    * @param uuid      the view unique identifier
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag value
    * @param rev       the revision that the event generates
    * @param instant   the instant when the event was emitted
    * @param subject   the subject that tagged the view
    */
  final case class CompositeViewTagAdded(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of a view deprecation.
    *
    * @param id      the view identifier
    * @param project the view parent project
    * @param uuid    the view unique identifier
    * @param rev     the revision that the event generates
    * @param instant the instant when the event was emitted
    * @param subject the subject that deprecated the view
    */
  final case class CompositeViewDeprecated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

}
