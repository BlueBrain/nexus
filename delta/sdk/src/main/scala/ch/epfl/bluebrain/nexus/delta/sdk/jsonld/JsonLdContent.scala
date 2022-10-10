package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import io.circe.Json

final case class JsonLdContent[A, M](resource: ResourceF[A], source: Json, metadata: Option[M])
