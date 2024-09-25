package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.FailedElemLogRow.FailedElemData
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{FailureReason, ProjectionMetadata}
import doobie._
import doobie.postgres.implicits._
import io.circe.{Encoder, Json}
import io.circe.generic.semiauto.deriveEncoder

import java.time.Instant

/**
  * The row of the failed_elem_log table
  */
final case class FailedElemLogRow(
    ordering: Offset,
    projectionMetadata: ProjectionMetadata,
    failedElemData: FailedElemData,
    instant: Instant
)

object FailedElemLogRow {
  private type Row =
    (
        Offset,
        String,
        String,
        Option[ProjectRef],
        Option[Iri],
        EntityType,
        Offset,
        Iri,
        Option[ProjectRef],
        Int,
        String,
        String,
        Option[String],
        Instant,
        Option[Json]
    )

  /**
    * Helper case class to structure FailedElemLogRow
    */
  final case class FailedElemData(
      id: Iri,
      project: Option[ProjectRef],
      entityType: EntityType,
      offset: Offset,
      rev: Int,
      reason: FailureReason
  )

  val context: ContextValue = ContextValue(contexts.error)

  implicit val failedElemDataEncoder: Encoder.AsObject[FailedElemData] =
    deriveEncoder[FailedElemData].mapJsonObject(_.remove("entityType"))

  implicit val failedElemDataJsonLdEncoder: JsonLdEncoder[FailedElemData] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit val failedElemLogRow: Read[FailedElemLogRow] = {
    Read[Row].map {
      case (
            ordering,
            name,
            module,
            project,
            resourceId,
            entityType,
            elemOffset,
            elemId,
            elemProject,
            revision,
            errorType,
            message,
            stackTrace,
            instant,
            details
          ) =>
        val reason = details
          .map { d =>
            FailureReason(errorType, message, d)
          }
          .getOrElse(FailureReason(errorType, message, stackTrace))

        FailedElemLogRow(
          ordering,
          ProjectionMetadata(module, name, project, resourceId),
          FailedElemData(elemId, elemProject, entityType, elemOffset, revision, reason),
          instant
        )
    }
  }
}
