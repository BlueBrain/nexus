package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json, JsonObject}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Composite view event enumeration.
  */
sealed trait CompositeViewEvent extends ScopedEvent {

  /**
    * @return
    *   the view identifier
    */
  def id: Iri

  /**
    * @return
    *   the project to which the view belongs
    */
  def project: ProjectRef

  /**
    * @return
    *   the view unique identifier
    */
  def uuid: UUID
}

object CompositeViewEvent {

  /**
    * Evidence of a view creation.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param uuid
    *   the view unique identifier
    * @param value
    *   the view value
    * @param source
    *   the original json value provided by the caller
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that created the view
    */
  final case class CompositeViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: CompositeViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of a view update.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param value
    *   the view value
    * @param source
    *   the original json value provided by the caller
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that updated the view
    */
  final case class CompositeViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: CompositeViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of tagging a view.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param uuid
    *   the view unique identifier
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag value
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that tagged the view
    */
  final case class CompositeViewTagAdded(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of a view deprecation.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param uuid
    *   the view unique identifier
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that deprecated the view
    */
  final case class CompositeViewDeprecated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  /**
    * Evidence of a view undeprecation.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param uuid
    *   the view unique identifier
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that undeprecated the view
    */
  final case class CompositeViewUndeprecated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends CompositeViewEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, CompositeViewEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                       = Serializer.circeConfiguration
    implicit val compositeViewValueCodec: Codec[CompositeViewValue] = CompositeViewValue.databaseCodec()
    implicit val codec: Codec.AsObject[CompositeViewEvent]          = deriveConfiguredCodec[CompositeViewEvent]
    Serializer.dropNulls()
  }

  val compositeViewMetricEncoder: ScopedEventMetricEncoder[CompositeViewEvent] =
    new ScopedEventMetricEncoder[CompositeViewEvent] {
      override def databaseDecoder: Decoder[CompositeViewEvent] = serializer.codec

      override def entityType: EntityType = CompositeViews.entityType

      override def eventToMetric: CompositeViewEvent => ProjectScopedMetric = event =>
        ProjectScopedMetric.from(
          event,
          event match {
            case _: CompositeViewCreated      => Created
            case _: CompositeViewUpdated      => Updated
            case _: CompositeViewTagAdded     => Tagged
            case _: CompositeViewDeprecated   => Deprecated
            case _: CompositeViewUndeprecated => Undeprecated
          },
          event.id,
          Set(nxv.View, compositeViewType),
          JsonObject.empty
        )
    }

  def sseEncoder(implicit base: BaseUri): SseEncoder[CompositeViewEvent] =
    new SseEncoder[CompositeViewEvent] {
      override val databaseDecoder: Decoder[CompositeViewEvent] = serializer.codec

      override def entityType: EntityType = CompositeViews.entityType

      override val selectors: Set[Label] = Set(Label.unsafe("views"), resourcesSelector)

      @nowarn("cat=unused")
      override val sseEncoder: Encoder.AsObject[CompositeViewEvent] = {
        val context                                                = ContextValue(Vocabulary.contexts.metadata, contexts.compositeViews)
        implicit val config: Configuration                         = Configuration.default
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
        implicit val subjectEncoder: Encoder[Subject]              = IriEncoder.jsonEncoder[Subject]
        implicit val viewValueEncoder: Encoder[CompositeViewValue] =
          Encoder.instance[CompositeViewValue](_ => Json.Null)
        implicit val projectRefEncoder: Encoder[ProjectRef]        = IriEncoder.jsonEncoder[ProjectRef]

        Encoder.encodeJsonObject.contramapObject { event =>
          deriveConfiguredEncoder[CompositeViewEvent]
            .encodeObject(event)
            .remove("value")
            .add(nxv.constrainedBy.prefix, schema.iri.asJson)
            .add(nxv.types.prefix, Set(nxv.View, compositeViewType).asJson)
            .add(nxv.resourceId.prefix, event.id.asJson)
            .add(keywords.context, context.value)
        }
      }
    }
}
