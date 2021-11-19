package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json, JsonObject}

import java.time.Instant
import scala.annotation.nowarn

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
  def rev: Long

  /**
    * @return
    *   the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject

  /**
    * @return
    *   the action performed
    */
  def action: Label

  /**
    * @return
    *   the id of the underlying resource
    */
  def resourceId: Iri

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

  val Created: Label    = Label.unsafe("Created")
  val Updated: Label    = Label.unsafe("Updated")
  val Tagged: Label     = Label.unsafe("Tagged")
  val TagDeleted: Label = Label.unsafe("TagDeleted")
  val Deprecated: Label = Label.unsafe("Deprecated")

  /**
    * Metric extracted from an event independent from an org/project
    */
  final case class UnscopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Long,
      action: Label,
      resourceId: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$resourceId-$rev"
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
      resourceId: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$organization-$resourceId-$rev"
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
      resourceId: Iri,
      types: Set[Iri],
      additionalFields: JsonObject
  ) extends EventMetric {
    def eventId: String = s"$project-$resourceId-$rev"
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
        "@id"     -> e.resourceId.asJson,
        "@type"   -> e.types.map { tpe =>
          Json.obj(
            "raw"   -> tpe.asJson,
            "short" -> tpe.toUri.toOption.flatMap { uri => uri.fragment.orElse(uri.path.lastSegment) }.asJson
          )
        }.asJson
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
