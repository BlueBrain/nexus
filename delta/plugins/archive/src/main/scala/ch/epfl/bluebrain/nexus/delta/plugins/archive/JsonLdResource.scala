package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import io.circe.Json

final case class JsonLdResource[A, M](
    resource: ResourceF[A],
    resourceEncoder: JsonLdEncoder[A],
    metadata: M,
    metadataEncoder: JsonLdEncoder[M],
    source: Json
)
