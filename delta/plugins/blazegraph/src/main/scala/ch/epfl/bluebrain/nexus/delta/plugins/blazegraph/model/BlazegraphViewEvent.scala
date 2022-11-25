package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
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
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json, JsonObject}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of Blazegraph view events.
  */
sealed trait BlazegraphViewEvent extends ScopedEvent {

  /**
    * @return
    *   the view identifier
    */
  def id: Iri

  /**
    * @return
    *   the project where the view belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the view unique identifier
    */
  def uuid: UUID

  /**
    * @return
    *   the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return
    *   the subject that created the view
    */
  def subject: Subject

  /**
    * @return
    *   the revision that the event generates
    */
  def rev: Int

  /**
    * @return
    *   the view type
    */
  def tpe: BlazegraphViewType

}

object BlazegraphViewEvent {

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
  final case class BlazegraphViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: BlazegraphViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent {
    override val tpe: BlazegraphViewType = value.tpe
  }

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
  final case class BlazegraphViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: BlazegraphViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent {
    override val tpe: BlazegraphViewType = value.tpe
  }

  /**
    * Evidence of tagging a view.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param tpe
    *   the view type
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
  final case class BlazegraphViewTagAdded(
      id: Iri,
      project: ProjectRef,
      tpe: BlazegraphViewType,
      uuid: UUID,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent

  /**
    * Evidence of a view deprecation.
    *
    * @param id
    *   the view identifier
    * @param project
    *   the view parent project
    * @param tpe
    *   the view type
    * @param uuid
    *   the view unique identifier
    * @param rev
    *   the revision that the event generates
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that deprecated the view
    */
  final case class BlazegraphViewDeprecated(
      id: Iri,
      project: ProjectRef,
      tpe: BlazegraphViewType,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends BlazegraphViewEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, BlazegraphViewEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration               = Serializer.circeConfiguration
    implicit val valueEncoder: Encoder[BlazegraphViewValue] =
      deriveConfiguredEncoder[BlazegraphViewValue].mapJson(_.deepDropNullValues)
    implicit val valueDecoder: Decoder[BlazegraphViewValue] =
      deriveConfiguredDecoder[BlazegraphViewValue]
    implicit val coder: Codec.AsObject[BlazegraphViewEvent] = deriveConfiguredCodec[BlazegraphViewEvent]
    Serializer()
  }

  val bgViewMetricEncoder: ScopedEventMetricEncoder[BlazegraphViewEvent] =
    new ScopedEventMetricEncoder[BlazegraphViewEvent] {
      override def databaseDecoder: Decoder[BlazegraphViewEvent] = serializer.codec

      override def entityType: EntityType = BlazegraphViews.entityType

      override def eventToMetric: BlazegraphViewEvent => ProjectScopedMetric = event =>
        ProjectScopedMetric.from(
          event,
          event match {
            case _: BlazegraphViewCreated    => Created
            case _: BlazegraphViewUpdated    => Updated
            case _: BlazegraphViewTagAdded   => Tagged
            case _: BlazegraphViewDeprecated => Deprecated
          },
          event.id,
          event.tpe.types,
          JsonObject.empty
        )
    }

  def sseEncoder(implicit base: BaseUri): SseEncoder[BlazegraphViewEvent] = new SseEncoder[BlazegraphViewEvent] {
    override val databaseDecoder: Decoder[BlazegraphViewEvent] = serializer.codec

    override def entityType: EntityType = BlazegraphViews.entityType

    override val selectors: Set[Label] = Set(Label.unsafe("views"), resourcesSelector)

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[BlazegraphViewEvent] = {
      val context                                                 = ContextValue(Vocabulary.contexts.metadata, contexts.blazegraph)
      implicit val config: Configuration                          = Configuration.default
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
      implicit val subjectEncoder: Encoder[Subject]               = IriEncoder.jsonEncoder[Subject]
      implicit val viewValueEncoder: Encoder[BlazegraphViewValue] =
        Encoder.instance[BlazegraphViewValue](_ => Json.Null)
      implicit val viewTpeEncoder: Encoder[BlazegraphViewType]    = Encoder.instance[BlazegraphViewType](_ => Json.Null)
      implicit val projectRefEncoder: Encoder[ProjectRef]         = IriEncoder.jsonEncoder[ProjectRef]

      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[BlazegraphViewEvent]
          .encodeObject(event)
          .remove("tpe")
          .remove("value")
          .add(nxv.types.prefix, event.tpe.types.asJson)
          .add(nxv.constrainedBy.prefix, schema.iri.asJson)
          .add(nxv.resourceId.prefix, event.id.asJson)
          .add(keywords.context, context.value)
      }
    }
  }
}
