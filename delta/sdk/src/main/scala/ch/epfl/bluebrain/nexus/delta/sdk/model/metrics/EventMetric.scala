package ch.epfl.bluebrain.nexus.delta.sdk.model.metrics

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.ScopedEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef}
import io.circe.syntax.KeyOps
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
    * Metric extracted from an event related to a project
    */
  final case class ProjectScopedMetric(
      instant: Instant,
      subject: Subject,
      rev: Int,
      action: Set[Label],
      project: ProjectRef,
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
        id,
        types,
        additionalFields
      )
  }

  implicit val eventMetricEncoder: Encoder.AsObject[EventMetric] = {

    implicit val subjectCodec: Encoder[Subject] = Identity.Database.subjectCodec
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
        case p: ProjectScopedMetric =>
          JsonObject(
            "project"      := p.project,
            "organization" := p.project.organization
          )
      }

      e.additionalFields.deepMerge(scoped).deepMerge(common)
    }
  }

  implicit val projectScopedMetricEncoder: Encoder.AsObject[ProjectScopedMetric] =
    eventMetricEncoder.contramapObject { identity }

}
