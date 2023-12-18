package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.EventMetric._
import ch.epfl.bluebrain.nexus.delta.sdk.model.metrics.ScopedEventMetricEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{resourcesSelector, SseEncoder}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, JsonObject}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of Project event types.
  */
sealed trait ProjectEvent extends ScopedEvent {

  /**
    * @return
    *   the project ref
    */
  def project: ProjectRef = ProjectRef(organizationLabel, label)

  /**
    * The relative [[Iri]] of the project
    */
  override def id: Iri = Projects.encodeId(project)

  /**
    * @return
    *   the project label
    */
  def label: Label

  /**
    * @return
    *   the project uuid
    */
  def uuid: UUID

  /**
    * @return
    *   the parent organization label
    */
  def organizationLabel: Label

  /**
    * @return
    *   the parent organization unique identifier
    */
  def organizationUuid: UUID

}

object ProjectEvent {

  /**
    * Evidence that a project has been created.
    *
    * @param label
    *   the project label
    * @param uuid
    *   the project uuid
    * @param organizationLabel
    *   the parent organization label
    * @param organizationUuid
    *   the parent organization uuid
    * @param rev
    *   the project revision
    * @param description
    *   an optional project description
    * @param apiMappings
    *   the project API mappings
    * @param base
    *   the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab
    *   an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param enforceSchema
    *   a flag to ban unconstrained resources in this project
    * @param instant
    *   the timestamp associated to this event
    * @param subject
    *   the identity associated to this event
    */
  final case class ProjectCreated(
      label: Label,
      uuid: UUID,
      override val organizationLabel: Label,
      organizationUuid: UUID,
      rev: Int,
      description: Option[String],
      apiMappings: ApiMappings,
      base: PrefixIri,
      vocab: PrefixIri,
      // TODO: Remove default after 1.10 migration
      enforceSchema: Boolean = false,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  object ProjectCreated {
    def apply(
        projectRef: ProjectRef,
        uuid: UUID,
        orgUUID: UUID,
        fields: ProjectFields,
        instant: Instant,
        subject: Subject
    )(implicit base: BaseUri): ProjectCreated =
      ProjectCreated(
        projectRef.project,
        uuid,
        projectRef.organization,
        orgUUID,
        1,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(projectRef),
        fields.vocabOrGenerated(projectRef),
        fields.enforceSchema,
        instant,
        subject
      )
  }

  /**
    * Evidence that a project has been updated.
    *
    * @param label
    *   the project label
    * @param uuid
    *   the project uuid
    * @param organizationLabel
    *   the parent organization label
    * @param organizationUuid
    *   the parent organization uuid
    * @param description
    *   an optional project description
    * @param apiMappings
    *   the project API mappings
    * @param base
    *   the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab
    *   an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param enforceSchema
    *   a flag to ban unconstrained resources in this project
    * @param rev
    *   the revision number that this event generates
    * @param instant
    *   the timestamp associated to this event
    * @param subject
    *   the identity associated to this event
    */
  final case class ProjectUpdated(
      label: Label,
      uuid: UUID,
      override val organizationLabel: Label,
      organizationUuid: UUID,
      rev: Int,
      description: Option[String],
      apiMappings: ApiMappings,
      base: PrefixIri,
      vocab: PrefixIri,
      // TODO: Remove default after 1.10 migration
      enforceSchema: Boolean = false,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  object ProjectUpdated {
    def apply(
        projectRef: ProjectRef,
        uuid: UUID,
        orgUUID: UUID,
        rev: Int,
        fields: ProjectFields,
        instant: Instant,
        subject: Subject
    )(implicit base: BaseUri): ProjectUpdated =
      ProjectUpdated(
        projectRef.project,
        uuid,
        projectRef.organization,
        orgUUID,
        rev,
        fields.description,
        fields.apiMappings,
        fields.baseOrGenerated(projectRef),
        fields.vocabOrGenerated(projectRef),
        fields.enforceSchema,
        instant,
        subject
      )
  }

  /**
    * Evidence that a project has been deprecated.
    *
    * @param label
    *   the label (segment) of the project
    * @param uuid
    *   the permanent identifier for the project
    * @param organizationLabel
    *   the parent organization label
    * @param organizationUuid
    *   the parent organization uuid
    * @param rev
    *   the revision number that this event generates
    * @param instant
    *   the timestamp associated to this event
    * @param subject
    *   the identity associated to this event
    */
  final case class ProjectDeprecated(
      label: Label,
      uuid: UUID,
      override val organizationLabel: Label,
      organizationUuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been undeprecated.
    *
    * @param label
    *   the label (segment) of the project
    * @param uuid
    *   the permanent identifier for the project
    * @param organizationLabel
    *   the parent organization label
    * @param organizationUuid
    *   the parent organization uuid
    * @param rev
    *   the revision number that this event generates
    * @param instant
    *   the timestamp associated to this event
    * @param subject
    *   the identity associated to this event
    */
  final case class ProjectUndeprecated(
      label: Label,
      uuid: UUID,
      override val organizationLabel: Label,
      organizationUuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been marked for deletion.
    *
    * @param label
    *   the label (segment) of the project
    * @param uuid
    *   the permanent identifier for the project
    * @param organizationLabel
    *   the parent organization label
    * @param organizationUuid
    *   the parent organization uuid
    * @param rev
    *   the revision number that this event generates
    * @param instant
    *   the timestamp associated to this event
    * @param subject
    *   the identity associated to this event
    */
  final case class ProjectMarkedForDeletion(
      label: Label,
      uuid: UUID,
      override val organizationLabel: Label,
      organizationUuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  @nowarn("cat=unused")
  val serializer: Serializer[ProjectRef, ProjectEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    // TODO: The `.withDefaults` method is used in order to inject the default empty remoteContexts
    //  when deserializing an event that has none. Remove it after 1.10 migration.
    implicit val configuration: Configuration = Serializer.circeConfiguration.withDefaults

    implicit val apiMappingsDecoder: Decoder[ApiMappings]          =
      Decoder.decodeMap[String, Iri].map(ApiMappings(_))
    implicit val apiMappingsEncoder: Encoder.AsObject[ApiMappings] =
      Encoder.encodeMap[String, Iri].contramapObject(_.value)

    implicit val coder: Codec.AsObject[ProjectEvent] = deriveConfiguredCodec[ProjectEvent]
    Serializer(Projects.encodeId)
  }

  def projectEventMetricEncoder(implicit base: BaseUri): ScopedEventMetricEncoder[ProjectEvent] =
    new ScopedEventMetricEncoder[ProjectEvent] {
      override def databaseDecoder: Decoder[ProjectEvent] = serializer.codec

      override def entityType: EntityType = Projects.entityType

      override def eventToMetric: ProjectEvent => ProjectScopedMetric = event =>
        ProjectScopedMetric.from(
          event,
          event match {
            case _: ProjectCreated           => Created
            case _: ProjectUpdated           => Updated
            case _: ProjectDeprecated        => Deprecated
            case _: ProjectUndeprecated      => Undeprecated
            case _: ProjectMarkedForDeletion => TagDeleted
          },
          ResourceUris.project(event.project).accessUri.toIri,
          Set(nxv.Project),
          JsonObject.empty
        )
    }

  def sseEncoder(implicit base: BaseUri): SseEncoder[ProjectEvent] =
    new SseEncoder[ProjectEvent] {

      override val databaseDecoder: Decoder[ProjectEvent] = serializer.codec

      override def entityType: EntityType = Projects.entityType

      override val selectors: Set[Label] = Set(Label.unsafe("projects"), resourcesSelector)

      @nowarn("cat=unused")
      override val sseEncoder: Encoder.AsObject[ProjectEvent] = {
        val context = ContextValue(contexts.metadata, contexts.projects)

        implicit val config: Configuration = Configuration.default
          .withDiscriminator(keywords.tpe)
          .copy(transformMemberNames = {
            case "label"             => nxv.label.prefix
            case "uuid"              => nxv.uuid.prefix
            case "organizationLabel" => nxv.organizationLabel.prefix
            case "organizationUuid"  => nxv.organizationUuid.prefix
            case "rev"               => nxv.rev.prefix
            case "instant"           => nxv.instant.prefix
            case "subject"           => nxv.eventSubject.prefix
            case other               => other
          })

        implicit val subjectEncoder: Encoder[Subject] = IriEncoder.jsonEncoder[Subject]
        Encoder.encodeJsonObject.contramapObject[ProjectEvent] { event =>
          deriveConfiguredEncoder[ProjectEvent]
            .encodeObject(event)
            .add("_projectId", ResourceUris.project(event.project).accessUri.asJson)
            .add(nxv.resourceId.prefix, ResourceUris.project(event.project).accessUri.asJson)
            .add(keywords.context, context.value)
        }
      }
    }
}
