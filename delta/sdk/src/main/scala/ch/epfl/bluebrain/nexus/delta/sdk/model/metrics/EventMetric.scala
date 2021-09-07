package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import com.datastax.oss.driver.api.core.uuid.Uuids
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, JsonObject}

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Metric extracted from a Delta event
  */
sealed trait EventMetric extends Product with Serializable {

  /**
    * UUID of the event
    */
  def uuid: UUID

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the revision this events generates
    */
  def rev: Long

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def action: Label

  /**
    * @return the id of the underlying resource
    */
  def id: Iri

  /**
    * @return the types of the underlying resource
    */
  def types: Set[Iri]

  /**
    * @return additional fields depending on the event
    */
  def additionalFields: JsonObject
}

object EventMetric {

  val Created: Label    = Label.unsafe("Created")
  val Updated: Label    = Label.unsafe("Updated")
  val Tagged: Label     = Label.unsafe("Tagged")
  val Deprecated: Label = Label.unsafe("Deprecated")

  /**
    * Metric extracted from an event independent from an org/project
    */
  final case class UnscopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Long,
      action: Label,
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def uuid: UUID = Uuids.nameBased(Uuids.startOf(instant.toEpochMilli), s"${types.mkString("-")}-$id-$rev")
  }

  /**
    * Metric extracted from an event related to organizations
    */
  final case class OrganizationMetric(
      instant: Instant,
      subject: Subject,
      rev: Long,
      action: Label,
      organization: Label,
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def uuid: UUID = Uuids.nameBased(Uuids.startOf(instant.toEpochMilli), s"$organization-$id-$rev")
  }

  /**
    * Metric extracted from an event related to a project
    */
  final case class ProjectScopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Long,
      action: Label,
      project: ProjectRef,
      organization: Label,
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def uuid: UUID = Uuids.nameBased(Uuids.startOf(instant.toEpochMilli), s"$project-$id-$rev")
  }

  object ProjectScopedMetric {
    def from[E <: ProjectScopedEvent](
        event: E,
        action: Label,
        id: Iri,
        types: Set[Iri],
        additionalFields: JsonObject
    ): ProjectScopedMetric =
      ProjectScopedMetric(
        event.instant,
        event.subject,
        event.rev,
        action,
        event.project,
        event.organizationLabel,
        id,
        types,
        additionalFields
      )
  }

  implicit val eventMetricEncoder: Encoder.AsObject[EventMetric] = {
    @nowarn("cat=unused")
    implicit val configuration: Configuration   = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val subjectCodec: Encoder[Subject] = deriveConfiguredEncoder[Subject]
    Encoder.AsObject.instance { e =>
      val common = JsonObject(
        "instant" -> e.instant.asJson,
        "subject" -> e.subject.asJson,
        "action"  -> e.action.asJson,
        "@id"     -> e.id.asJson,
        "@type"   -> e.types.asJson
      )

      val scoped = e match {
        case _: UnscopedMetric      => JsonObject.empty
        case o: OrganizationMetric  =>
          JsonObject(
            "organization" -> o.organization.asJson
          )
        case p: ProjectScopedMetric =>
          JsonObject(
            "project"      -> p.project.asJson,
            "organization" -> p.organization.asJson
          )
      }

      e.additionalFields.deepMerge(scoped).deepMerge(common)
    }
  }

}
