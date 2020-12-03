package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

import scala.annotation.nowarn

/**
  * Enumeration of Permissions event types.
  */
sealed trait PermissionsEvent extends Event

object PermissionsEvent {

  /**
    * A witness to a collection of permissions appended to the set.
    *
    * @param rev         the revision this event generated
    * @param permissions the collection of permissions appended to the set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsAppended(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to a collection of permissions subtracted from the set.
    *
    * @param rev         the revision this event generated
    * @param permissions the collection of permissions subtracted from the set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsSubtracted(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being replaced.
    *
    * @param rev         the revision this event generated
    * @param permissions the new set of permissions that replaced the previous set
    * @param instant     the instant when the event was emitted
    * @param subject     the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsReplaced(
      rev: Long,
      permissions: Set[Permission],
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  /**
    * A witness to the permission set being deleted (emptied).
    *
    * @param rev     the revision this event generated
    * @param instant the instant when the event was emitted
    * @param subject the subject that performed the action that resulted in emitting this event
    */
  final case class PermissionsDeleted(
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends PermissionsEvent

  private val context = ContextValue(contexts.metadata, contexts.permissions)

  @nowarn("cat=unused")
  implicit final def permissionsEventJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[PermissionsEvent] = {
    implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder

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

    implicit val encoder: Encoder.AsObject[PermissionsEvent] = Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[PermissionsEvent]
        .mapJsonObject(_.add("_permissionsId", ResourceUris.permissions.accessUri.asJson))
        .encodeObject(ev)
    }

    ResourceUris.permissions.accessUri
    JsonLdEncoder.computeFromCirce[PermissionsEvent](context)
  }
}
