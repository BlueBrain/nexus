package ch.epfl.bluebrain.nexus.admin.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.config.Contexts._
import ch.epfl.bluebrain.nexus.delta.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.delta.config.Vocabulary.nxv
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto._
import io.circe.syntax._
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends Product with Serializable {

  /**
    * @return the permanent identifier for the organization
    */
  def id: UUID

  /**
    * @return the revision that this event generated
    */
  def rev: Long

  /**
    * @return the instant when this event was created
    */
  def instant: Instant

  /**
    * @return the subject which created this event
    */
  def subject: Subject
}

object OrganizationEvent {

  /**
    * Event representing organization creation.
    *
    * @param id           the permanent identifier of the organization
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationCreated(
      id: UUID,
      label: String,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent {

    /**
      *  the revision number that this event generates
      */
    val rev: Long = 1L
  }

  /**
    * Event representing organization update.
    *
    * @param id           the permanent identifier of the organization
    * @param rev          the update revision
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationUpdated(
      id: UUID,
      rev: Long,
      label: String,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    *  Event representing organization deprecation.
    *
    * @param id           the permanent identifier of the organization
    * @param rev          the deprecation revision
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationDeprecated(
      id: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  object JsonLd {

    @nowarn("cat=unused")
    implicit private val config: Configuration = Configuration.default
      .withDiscriminator("@type")
      .copy(transformMemberNames = {
        case nxv.`@id`.name        => nxv.uuid.prefix
        case nxv.label.name        => nxv.label.prefix
        case nxv.rev.name          => nxv.rev.prefix
        case nxv.instant.name      => nxv.instant.prefix
        case nxv.eventSubject.name => nxv.eventSubject.prefix
        case other                 => other
      })

    @nowarn("cat=unused")
    implicit private def subjectIdEncoder(implicit http: HttpConfig): Encoder[Subject] =
      Encoder.encodeJson.contramap(_.id.asJson)

    @nowarn("cat=unused")
    implicit final def orgEventEncoder(implicit http: HttpConfig): Encoder[OrganizationEvent] =
      Encoder.encodeJson.contramap[OrganizationEvent] { ev =>
        deriveConfiguredEncoder[OrganizationEvent]
          .mapJson { json =>
            val rev = Json.obj(nxv.rev.prefix -> Json.fromLong(ev.rev))
            json
              .deepMerge(rev)
              .addContext(adminCtxUri)
              .addContext(resourceCtxUri)
          }
          .apply(ev)
      }
  }
}
