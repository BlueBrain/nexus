package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, TagLabel}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

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

  private val context = ContextValue(Vocabulary.contexts.metadata, contexts.compositeViews)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"      => "_viewId"
      case "source"  => nxv.source.prefix
      case "project" => nxv.project.prefix
      case "rev"     => nxv.rev.prefix
      case "instant" => nxv.instant.prefix
      case "subject" => nxv.eventSubject.prefix
      case "uuid"    => "_uuid"
      case other     => other
    })

  @nowarn("cat=unused")
  implicit def compositeEventEncoder(implicit baseUri: BaseUri): Encoder.AsObject[CompositeViewEvent] = {
    implicit val subjectEncoder: Encoder[Subject]              = Identity.subjectIdEncoder
    implicit val identityEncoder: Encoder.AsObject[Identity]   = Identity.persistIdentityDecoder
    implicit val viewValueEncoder: Encoder[CompositeViewValue] =
      Encoder.instance[CompositeViewValue](_ => Json.Null)
    deriveConfiguredEncoder[CompositeViewEvent].mapJsonObject(
      _.add(nxv.constrainedBy.prefix, schema.iri.asJson)
        .add(nxv.types.prefix, Set(nxv.View, compositeViewType).asJson)
        .add(keywords.context, context.value)
    )
  }

}
