package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

final case class SupervisedDescription(
    metadata: ProjectionMetadata,
    executionStrategy: ExecutionStrategy,
    restarts: Int,
    status: ExecutionStatus,
    progress: ProjectionProgress
)

object SupervisedDescription {
  implicit final val supervisedDescriptionEncoder: Encoder.AsObject[SupervisedDescription] =
    deriveEncoder

  implicit final val supervisedDescriptionJsonLdEncoder : JsonLdEncoder[SupervisedDescription] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))

  implicit final val supervisedDescriptionJsonLdEncoder2: JsonLdEncoder[List[SupervisedDescription]] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.error))
}
