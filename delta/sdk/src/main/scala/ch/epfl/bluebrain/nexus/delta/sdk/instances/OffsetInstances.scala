package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.utils.OffsetUtils

trait OffsetInstances {
  implicit val offsetJsonLdEncoder: JsonLdEncoder[Offset]          = OffsetUtils.offsetJsonLdEncoder
  implicit val noOffsetJsonLdEncoder: JsonLdEncoder[NoOffset.type] = OffsetUtils.noOffsetJsonLdEncoder
}

object OffsetInstances extends OffsetInstances
