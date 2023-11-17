package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.circe.{dropNullValues, JsonObjOps}
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.jsonld.RemoteContextRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef, ResourceRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe._

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of resource event states
  */
sealed trait ResourceEvent extends ScopedEvent {

  /**
    * @return
    *   the resource identifier
    */
  def id: Iri

  /**
    * @return
    *   the project where the resource belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the collection of known resource types
    */
  def types: Set[Iri]

}

object ResourceEvent {

  /**
    * Event representing a resource creation.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schema
    *   the schema used to constrain the resource
    * @param schemaProject
    *   the project where the schema belongs
    * @param types
    *   the collection of known resource types
    * @param source
    *   the representation of the resource as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the resource
    * @param expanded
    *   the expanded JSON-LD representation of the resource
    * @param remoteContexts
    *   the remote contexts of the resource
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    * @param tag
    *   an optional user-specified tag attached to the first revision of this resource
    */
  final case class ResourceCreated(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      // TODO: Remove default after 1.10 migration
      remoteContexts: Set[RemoteContextRef] = Set.empty,
      rev: Int,
      instant: Instant,
      subject: Subject,
      tag: Option[UserTag]
  ) extends ResourceEvent

  /**
    * Event representing a resource modification.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schema
    *   the schema used to constrain the resource
    * @param schemaProject
    *   the project where the schema belongs
    * @param types
    *   the collection of known resource types
    * @param source
    *   the representation of the resource as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the resource
    * @param expanded
    *   the expanded JSON-LD representation of the resource
    * @param remoteContexts
    *   the remote contexts of the resource
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    * @param tag
    *   an optional user-specified tag attached to the current resource revision when updated
    */
  final case class ResourceUpdated(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      // TODO: Remove default after 1.10 migration
      remoteContexts: Set[RemoteContextRef] = Set.empty,
      rev: Int,
      instant: Instant,
      subject: Subject,
      tag: Option[UserTag]
  ) extends ResourceEvent

  final case class ResourceSchemaUpdated(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      rev: Int,
      instant: Instant,
      subject: Subject,
      tag: Option[UserTag]
  ) extends ResourceEvent

  /**
    * Event representing a resource refresh.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schema
    *   the schema used to constrain the resource
    * @param schemaProject
    *   the project where the schema belongs
    * @param types
    *   the collection of known resource types
    * @param compacted
    *   the compacted JSON-LD representation of the resource
    * @param expanded
    *   the expanded JSON-LD representation of the resource
    * @param remoteContexts
    *   the remote contexts of the resource
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResourceRefreshed(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      // TODO: Remove default after 1.10 migration
      remoteContexts: Set[RemoteContextRef] = Set.empty,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a tag addition to a resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param types
    *   the collection of known resource types
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''targetRev''
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResourceTagAdded(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a tag deletion from a resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param types
    *   the collection of known resource types
    * @param tag
    *   the tag that was deleted
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResourceTagDeleted(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      tag: UserTag,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a resource deprecation.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param types
    *   the collection of known resource types
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResourceDeprecated(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a resource undeprecation.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param types
    *   the collection of known resource types
    * @param rev
    *   the resource revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class ResourceUndeprecated(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Iri, ResourceEvent] = {
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.CompactedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._

    // TODO: The `.withDefaults` method is used in order to inject the default empty remoteContexts
    //  when deserializing an event that has none. Remove it after 1.10 migration.
    implicit val configuration: Configuration = Serializer.circeConfiguration.withDefaults

    implicit val enc: Encoder.AsObject[ResourceEvent] =
      deriveConfiguredEncoder[ResourceEvent].mapJsonObject(dropNullValues)
    implicit val coder: Codec.AsObject[ResourceEvent] = Codec.AsObject.from(deriveConfiguredDecoder[ResourceEvent], enc)
    Serializer()
  }

  val resourceEventMetricEncoder: ScopedEventMetricEncoder[ResourceEvent] =
    new ScopedEventMetricEncoder[ResourceEvent] {
      override def databaseDecoder: Decoder[ResourceEvent] = serializer.codec

      override def entityType: EntityType = Resources.entityType

      override def eventToMetric: ResourceEvent => ProjectScopedMetric = event =>
        ProjectScopedMetric.from(
          event,
          event match {
            case _: ResourceCreated       => Created
            case _: ResourceUpdated       => Updated
            case _: ResourceRefreshed     => Refreshed
            case _: ResourceTagAdded      => Tagged
            case _: ResourceTagDeleted    => TagDeleted
            case _: ResourceDeprecated    => Deprecated
            case _: ResourceUndeprecated  => Undeprecated
            case _: ResourceSchemaUpdated => Updated
          },
          event.id,
          event.types,
          JsonObject.empty
        )
    }

  def sseEncoder(implicit base: BaseUri): SseEncoder[ResourceEvent] = new SseEncoder[ResourceEvent] {

    override val databaseDecoder: Decoder[ResourceEvent] = serializer.codec

    override def entityType: EntityType = Resources.entityType

    override val selectors: Set[Label] = Set(resourcesSelector)

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[ResourceEvent] = {
      val context                        = ContextValue(contexts.metadata)
      implicit val config: Configuration = Configuration.default
        .withDiscriminator(keywords.tpe)
        .copy(transformMemberNames = {
          case "id"            => nxv.resourceId.prefix
          case "types"         => nxv.types.prefix
          case "source"        => nxv.source.prefix
          case "project"       => nxv.project.prefix
          case "rev"           => nxv.rev.prefix
          case "instant"       => nxv.instant.prefix
          case "subject"       => nxv.eventSubject.prefix
          case "schemaProject" => nxv.schemaProject.prefix
          case "schema"        => nxv.constrainedBy.prefix
          case other           => other
        })

      implicit val compactedJsonLdEncoder: Encoder[CompactedJsonLd]    = Encoder.instance(_.json)
      implicit val constrainedByEncoder: Encoder[ResourceRef.Revision] = Encoder.instance(_.iri.asJson)
      implicit val expandedJsonLdEncoder: Encoder[ExpandedJsonLd]      = Encoder.instance(_.json)

      implicit val subjectEncoder: Encoder[Subject]       = IriEncoder.jsonEncoder[Subject]
      implicit val projectRefEncoder: Encoder[ProjectRef] = IriEncoder.jsonEncoder[ProjectRef]
      Encoder.encodeJsonObject.contramapObject { event =>
        val obj = deriveConfiguredEncoder[ResourceEvent].encodeObject(event)
        obj.dropNulls
          .remove("compacted")
          .remove("expanded")
          .remove("remoteContexts")
          .add(keywords.context, context.value)
      }
    }
  }
}
