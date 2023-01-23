package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import scala.annotation.nowarn

/**
  * Describes the remaining elements to stream
  * @param count
  *   the number of remaining elements
  * @param maxInstant
  *   the instant of the last element
  */
final case class RemainingElems(count: Long, maxInstant: Instant)

object RemainingElems {

  implicit final val remainingElemsCodec: Codec[RemainingElems] = {
    @nowarn("cat=unused")
    implicit val configuration: Configuration =
      Configuration.default.withDiscriminator(keywords.tpe)
    deriveConfiguredCodec[RemainingElems]
  }

  implicit val remainingElemsJsonLdEncoder: JsonLdEncoder[RemainingElems] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.offset))

}
