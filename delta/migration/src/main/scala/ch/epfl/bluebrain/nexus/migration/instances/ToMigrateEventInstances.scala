package ch.epfl.bluebrain.nexus.migration.instances

import akka.http.scaladsl.model.{ContentType, Uri}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.migration.v1_4.events.{EventDeserializationFailed, ToMigrateEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.admin.{OrganizationEvent, ProjectEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.iam.{AccessControlList, AclEvent, PermissionsEvent, RealmEvent}
import ch.epfl.bluebrain.nexus.migration.v1_4.events.kg.{Digest, Event, FileAttributes, StorageFileAttributes, StorageReference}
import io.circe.{Encoder, Json}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType.Camel._
import io.circe.generic.extras.Configuration
import io.circe.syntax._
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

trait ToMigrateEventInstances {

  @nowarn("cat=unused")
  implicit val toMigrateEventEncoder: Encoder[ToMigrateEvent] = Encoder.instance { event =>
    implicit val config: Configuration = Configuration.default.withDiscriminator("@type")

    implicit val subjectEncoder: Encoder[Subject]   = deriveConfiguredEncoder[Subject]
    implicit val identityEncoder: Encoder[Identity] = deriveConfiguredEncoder[Identity]

    def aclArrayEncoder: Encoder[AccessControlList] =
      Encoder.encodeJson.contramap { case AccessControlList(value) =>
        val acl = value.map { case (identity, perms) =>
          Json.obj("identity" -> identity.asJson, "permissions" -> perms.asJson)
        }
        Json.arr(acl.toSeq: _*)
      }

    implicit def aclEncoder: Encoder[AccessControlList]            =
      aclArrayEncoder.mapJson { array => Json.obj("acl" -> array) }

    implicit val permissionEventEncoder: Encoder[PermissionsEvent] = deriveConfiguredEncoder[PermissionsEvent]
    implicit val aclEventEncoder: Encoder[AclEvent]                = deriveConfiguredEncoder[AclEvent]
    implicit val realmEventEncoder: Encoder[RealmEvent]            = deriveConfiguredEncoder[RealmEvent]
    implicit val projectEventEncoder: Encoder[ProjectEvent]        = deriveConfiguredEncoder[ProjectEvent]
    implicit val orgEventEncoder: Encoder[OrganizationEvent]       = deriveConfiguredEncoder[OrganizationEvent]

    implicit val digestEncoder: Encoder[Digest]                               = deriveConfiguredEncoder[Digest]
    implicit val contentTypeEncoder: Encoder[ContentType]                     = Encoder.encodeString.contramap(_.value)
    implicit val uriPathEncoder: Encoder[Uri.Path]                            = Encoder.encodeString.contramap(_.toString)
    implicit val fileAttributesEncoder: Encoder[FileAttributes]               = deriveConfiguredEncoder[FileAttributes]
    implicit val storageFileAttributesEncoder: Encoder[StorageFileAttributes] =
      deriveConfiguredEncoder[StorageFileAttributes]
    implicit val storageReferenceEncoder: Encoder[StorageReference]           = deriveConfiguredEncoder[StorageReference]
    implicit val eventEncoder: Encoder[Event]                                 = deriveConfiguredEncoder[Event]

    implicit val eventDeserializationFailedEncoder: Encoder[EventDeserializationFailed] =
      deriveConfiguredEncoder[EventDeserializationFailed]

    event match {
      case p: PermissionsEvent           => p.asJson
      case a: AclEvent                   => a.asJson
      case r: RealmEvent                 => r.asJson
      case o: OrganizationEvent          => o.asJson
      case p: ProjectEvent               => p.asJson
      case e: Event                      => e.asJson
      case e: EventDeserializationFailed => e.asJson
    }
  }

}
