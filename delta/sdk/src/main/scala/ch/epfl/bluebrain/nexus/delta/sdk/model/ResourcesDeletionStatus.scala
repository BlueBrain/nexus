package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.{Decoder, Encoder}

import java.time.Instant
import scala.annotation.nowarn

/**
  * The resources deletion status
  *
  * @param progress         the current deletion progress
  * @param project          the project where the deletion is happening
  * @param projectCreatedBy the subject who created the project
  * @param projectCreatedAt the subject who created the project
  * @param createdBy        the subject who initiated the deletion of resources
  * @param createdAt        the time when the deletion of resources was initiated
  * @param updatedAt        the last time when the deletion of resources was updated
  */
final case class ResourcesDeletionStatus(
    progress: ResourcesDeletionProgress,
    project: ProjectRef,
    projectCreatedBy: Subject,
    projectCreatedAt: Instant,
    createdBy: Subject,
    createdAt: Instant,
    updatedAt: Instant
)

object ResourcesDeletionStatus {

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "progress" => "progress"
    case other      => s"_$other"
  })

  @nowarn("cat=unused")
  implicit def resourcesDeletionStatusEncoder(implicit base: BaseUri): Encoder[ResourcesDeletionStatus] = {
    implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder(base)
    deriveConfiguredEncoder[ResourcesDeletionStatus]
  }

  @nowarn("cat=unused")
  implicit def resourcesDeletionStatusDecoder(implicit base: BaseUri): Decoder[ResourcesDeletionStatus] = {
    implicit val subjectDecoder: Decoder[Subject] = Identity.subjectIdDecoder(base)
    deriveConfiguredDecoder
  }

  implicit def resourcesDeletionStatusJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourcesDeletionStatus] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.deletionStatus))
}
