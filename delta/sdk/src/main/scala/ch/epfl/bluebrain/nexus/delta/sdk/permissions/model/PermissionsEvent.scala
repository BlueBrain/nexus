package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Encoder}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of Permissions event types.
  */
sealed trait PermissionsEvent extends GlobalEvent

object PermissionsEvent {

  /**
    * A witness to a collection of permissions appended to the set.
    *
    * @param rev
    *   the revision this event generated
    * @param permissions
    *   the collection of permissions appended to the set
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsAppended(
      rev: Int,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to a collection of permissions subtracted from the set.
    *
    * @param rev
    *   the revision this event generated
    * @param permissions
    *   the collection of permissions subtracted from the set
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsSubtracted(
      rev: Int,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being replaced.
    *
    * @param rev
    *   the revision this event generated
    * @param permissions
    *   the new set of permissions that replaced the previous set
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsReplaced(
      rev: Int,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being deleted (emptied).
    *
    * @param rev
    *   the revision this event generated
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsDeleted(
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Label, PermissionsEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration            = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[PermissionsEvent] = deriveConfiguredCodec[PermissionsEvent]
    Serializer(_ => Permissions.entityId)
  }

  @nowarn("cat=unused")
  val sseEncoder: SseEncoder[PermissionsEvent] = new SseEncoder[PermissionsEvent] {
    private val context = ContextValue(contexts.metadata, contexts.permissions)

    implicit val derivationConfiguration: Configuration =
      Configuration(
        transformMemberNames = {
          case "rev"     => "_rev"
          case "instant" => "_instant"
          case "subject" => "_subject"
          case other     => other
        },
        transformConstructorNames = identity,
        useDefaults = false,
        discriminator = Some(keywords.tpe),
        strictDecoding = false
      )

    override def apply(implicit base: BaseUri): Encoder.AsObject[PermissionsEvent] = {
      implicit val subjectEncoder: Encoder[Subject] = IriEncoder.jsonEncoder[Subject]
      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[PermissionsEvent]
          .encodeObject(event)
          .add("_permissionsId", ResourceUris.permissions.accessUri.asJson)
          .add(keywords.context, context.value)
      }
    }
  }
}
