package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * ElasticSearch view event enumeration.
  */
sealed trait ElasticSearchViewEvent extends ScopedEvent {

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
    *   the view type
    */
  def tpe: ElasticSearchViewType

}

object ElasticSearchViewEvent {

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
  final case class ElasticSearchViewCreated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent {
    override val tpe: ElasticSearchViewType = value.tpe
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
  final case class ElasticSearchViewUpdated(
      id: Iri,
      project: ProjectRef,
      uuid: UUID,
      value: ElasticSearchViewValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent {
    override val tpe: ElasticSearchViewType = value.tpe
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
  final case class ElasticSearchViewTagAdded(
      id: Iri,
      project: ProjectRef,
      tpe: ElasticSearchViewType,
      uuid: UUID,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

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
  final case class ElasticSearchViewDeprecated(
      id: Iri,
      project: ProjectRef,
      tpe: ElasticSearchViewType,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ElasticSearchViewEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ElasticSearchViewEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                               = Serializer.circeConfiguration
    implicit val elasticSearchValueEncoder: Encoder[ElasticSearchViewValue] =
      deriveConfiguredEncoder[ElasticSearchViewValue].mapJson(_.deepDropNullValues)
    implicit val elasticSearchValueDecoder: Decoder[ElasticSearchViewValue] =
      deriveConfiguredDecoder[ElasticSearchViewValue]
    implicit val coder: Codec.AsObject[ElasticSearchViewEvent]              = deriveConfiguredCodec[ElasticSearchViewEvent]
    Serializer(_.id)
  }

  def sseEncoder(implicit base: BaseUri): SseEncoder[ElasticSearchViewEvent] = new SseEncoder[ElasticSearchViewEvent] {
    override val databaseDecoder: Decoder[ElasticSearchViewEvent] = serializer.codec

    override def entityType: EntityType = ElasticSearchViews.entityType

    override val selectors: Set[Label] = Set(Label.unsafe("views"), resourcesSelector)

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[ElasticSearchViewEvent] = {
      val context                                                    = ContextValue(Vocabulary.contexts.metadata, contexts.elasticsearch)
      implicit val config: Configuration                             = Configuration.default
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
      implicit val subjectEncoder: Encoder[Subject]                  = IriEncoder.jsonEncoder[Subject]
      implicit val viewValueEncoder: Encoder[ElasticSearchViewValue] = Encoder.instance(_ => Json.Null)
      implicit val viewTpeEncoder: Encoder[ElasticSearchViewType]    = Encoder.instance(_ => Json.Null)
      implicit val projectRefEncoder: Encoder[ProjectRef]            = IriEncoder.jsonEncoder[ProjectRef]

      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[ElasticSearchViewEvent]
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
