package ch.epfl.bluebrain.nexus.delta.sdk.instances

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.utils.OffsetUtils

trait OffsetInstances {
  implicit val offsetJsonLdEncoder: JsonLdEncoder[Offset] = OffsetUtils.offsetJsonLdEncoder
}

object OffsetInstances extends OffsetInstances
