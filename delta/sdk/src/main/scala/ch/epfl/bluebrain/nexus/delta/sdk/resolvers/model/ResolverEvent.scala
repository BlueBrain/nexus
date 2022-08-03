package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity, Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of Resolver event types.
  */
sealed trait ResolverEvent extends ScopedEvent {

  /**
    * @return
    *   the resolver identifier
    */
  def id: Iri

  /**
    * @return
    *   the project where the resolver belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the resolver type
    */
  def tpe: ResolverType

}

object ResolverEvent {

  /**
    * Event for the creation of a resolver
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param value
    *   additional fields to configure the resolver
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResolverCreated(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent {
    override val tpe: ResolverType = value.tpe
  }

  /**
    * Event for the modification of an existing resolver
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param value
    *   additional fields to configure the resolver
    * @param rev
    *   the last known revision of the resolver
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResolverUpdated(
      id: Iri,
      project: ProjectRef,
      value: ResolverValue,
      source: Json,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent {
    override val tpe: ResolverType = value.tpe
  }

  /**
    * Event for to tag a resolver
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param tpe
    *   the resolver type
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the resolver
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject creating this event
    */
  final case class ResolverTagAdded(
      id: Iri,
      project: ProjectRef,
      tpe: ResolverType,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  /**
    * Event for the deprecation of a resolver
    *
    * @param id
    *   the resolver identifier
    * @param project
    *   the project the resolver belongs to
    * @param tpe
    *   the resolver type
    * @param rev
    *   the last known revision of the resolver
    * @param instant
    *   the instant this event was created
    * @param subject
    *   the subject creating this event
    */
  final case class ResolverDeprecated(
      id: Iri,
      project: ProjectRef,
      tpe: ResolverType,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResolverEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ResolverEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                      = Serializer.circeConfiguration
    implicit val resolverValueCodec: Codec.AsObject[ResolverValue] = deriveConfiguredCodec[ResolverValue]
    implicit val coder: Codec.AsObject[ResolverEvent]              = deriveConfiguredCodec[ResolverEvent]
    Serializer(_.id)
  }

  def sseEncoder(implicit base: BaseUri): SseEncoder[ResolverEvent] = new SseEncoder[ResolverEvent] {

    override val databaseDecoder: Decoder[ResolverEvent] = serializer.codec

    override def entityType: EntityType = Resolvers.entityType

    override val selectors: Set[Label] = Set(Label.unsafe("resolvers"), resourcesSelector)

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[ResolverEvent] = {
      val context                                               = ContextValue(contexts.metadata, contexts.resolvers)
      implicit val config: Configuration                        = Configuration.default
        .withDiscriminator(keywords.tpe)
        .copy(transformMemberNames = {
          case "id"      => nxv.resolverId.prefix
          case "source"  => nxv.source.prefix
          case "project" => nxv.project.prefix
          case "rev"     => nxv.rev.prefix
          case "instant" => nxv.instant.prefix
          case "subject" => nxv.eventSubject.prefix
          case other     => other
        })
      implicit val subjectEncoder: Encoder[Subject]             = IriEncoder.jsonEncoder[Subject]
      implicit val identityEncoder: Encoder.AsObject[Identity]  = Identity.Database.identityCodec
      implicit val resolverValueEncoder: Encoder[ResolverValue] = Encoder.instance[ResolverValue](_ => Json.Null)
      implicit val projectRefEncoder: Encoder[ProjectRef]       = IriEncoder.jsonEncoder[ProjectRef]
      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[ResolverEvent]
          .encodeObject(event)
          .remove("tpe")
          .remove("value")
          .add(nxv.types.prefix, event.tpe.types.asJson)
          .add(nxv.constrainedBy.prefix, schemas.resolvers.asJson)
          .add(nxv.resourceId.prefix, event.id.asJson)
          .add(keywords.context, context.value)
      }
    }
  }
}
