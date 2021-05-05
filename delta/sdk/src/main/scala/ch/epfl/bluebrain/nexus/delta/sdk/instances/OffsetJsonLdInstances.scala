package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.instances.OffsetInstances

trait OffsetJsonLdInstances extends OffsetInstances {
  implicit val offsetJsonLdEncoder: JsonLdEncoder[Offset] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.offset))

  implicit val noOffsetJsonLdEncoder: JsonLdEncoder[NoOffset.type] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.offset))
}

object OffsetJsonLdInstances extends OffsetJsonLdInstances
