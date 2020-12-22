package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, TagLabel}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.Json

import java.time.Instant
import java.util.UUID

/**
  * ElasticSearch view event enumeration.
  */
sealed trait ElasticSearchViewEvent extends Event {

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
}

object ElasticSearchViewEvent {

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
  final case class ElasticSearchViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

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
  final case class ElasticSearchViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

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
  final case class ElasticSearchViewTagAdded(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

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
  final case class ElasticSearchViewDeprecated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

}
