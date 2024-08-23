package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.{EncoderOps, KeyOps}
import io.circe.{Encoder, JsonObject}

import java.time.Instant

/**
  * Metric extracted from a Delta event
  */
sealed trait EventMetric extends Product with Serializable {

  /**
    * Identifier of the event
    */
  def eventId: String

  /**
    * @return
    *   the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return
    *   the revision this events generates
    */
  def rev: Int

  /**
    * @return
    *   the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject

  /**
    * @return
    *   the action performed
    */
  def action: Set[Label]

  /**
    * @return
    *   the id of the underlying resource
    */
  def id: Iri

  /**
    * @return
    *   the types of the underlying resource
    */
  def types: Set[Iri]

  /**
    * @return
    *   additional fields depending on the event
    */
  def additionalFields: JsonObject
}

object EventMetric {

  val Created: Label      = Label.unsafe("Created")
  val Updated: Label      = Label.unsafe("Updated")
  val Refreshed: Label    = Label.unsafe("Refreshed")
  val Tagged: Label       = Label.unsafe("Tagged")
  val TagDeleted: Label   = Label.unsafe("TagDeleted")
  val Deprecated: Label   = Label.unsafe("Deprecated")
  val Undeprecated: Label = Label.unsafe("Undeprecated")
  val Cancelled: Label    = Label.unsafe("Cancelled")
  val Deleted: Label      = Label.unsafe("Deleted")

  /**
    * Metric extracted from an event independent from an org/project
    */
  final case class UnscopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Int,
      action: Set[Label],
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$id-$rev"
  }

  /**
    * Metric extracted from an event related to organizations
    */
  final case class OrganizationMetric(
      instant: Instant,
      subject: Subject,
      rev: Int,
      action: Set[Label],
      organization: Label,
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$organization-$id-$rev"
  }

  /**
    * Metric extracted from an event related to a project
    */
  final case class ProjectScopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Int,
      action: Set[Label],
      project: ProjectRef,
      organization: Label,
      id: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$project/$id:$rev"
  }

  object ProjectScopedMetric {
    def from[E <: ScopedEvent](
        event: E,
        action: Set[Label],
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
        event.project.organization,
        id,
        types,
        additionalFields
      )

    def from[E <: ScopedEvent](
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
        Set(action),
        event.project,
        event.project.organization,
        id,
        types,
        additionalFields
      )
  }

  implicit val eventMetricEncoder: Encoder.AsObject[EventMetric] = {

    implicit val configuration: Configuration   = Configuration.default.withDiscriminator(keywords.tpe)
    implicit val subjectCodec: Encoder[Subject] = deriveConfiguredEncoder[Subject]
    Encoder.AsObject.instance { e =>
      val common = JsonObject(
        "instant" := e.instant,
        "subject" := e.subject,
        "action"  := e.action,
        "@id"     := e.id,
        "rev"     := e.rev,
        "@type"   := e.types
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

  implicit val projectScopedMetricEncoder: Encoder.AsObject[ProjectScopedMetric] =
    eventMetricEncoder.contramapObject {
      identity
    }

}
