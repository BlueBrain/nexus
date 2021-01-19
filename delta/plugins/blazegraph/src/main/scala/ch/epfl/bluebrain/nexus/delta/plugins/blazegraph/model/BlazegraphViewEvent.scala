package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of Blazegraph view events.
  */
sealed trait BlazegraphViewEvent extends Event {

  /**
    * @return the view identifier
    */
  def id: Iri

  /**
    * @return the project where the view belongs to
    */
  def project: ProjectRef

  /**
    * @return the view unique identifier
    */
  def uuid: UUID

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the subject that created the view
    */
  def subject: Subject

  /**
    * @return the revision that the event generates
    */
  def rev: Long
}

object BlazegraphViewEvent {

  /**
    * Evidence of a view creation.
    *
    * @param id       the view identifier
    * @param project  the view parent project
    * @param uuid     the view unique identifier
    * @param value    the view value
    * @param source   the original json value provided by the caller
    * @param rev      the revision that the event generates
    * @param instant  the instant when the event was emitted
    * @param subject  the subject that created the view
    */
  final case class BlazegraphViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: BlazegraphViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent

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
  final case class BlazegraphViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: BlazegraphViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent

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
  final case class BlazegraphViewTagAdded(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent

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
  final case class BlazegraphViewDeprecated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent
}
