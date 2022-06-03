package ch.epfl.bluebrain.nexus.delta.sdk.sse

import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.Encoder

trait SseSerializer[E] {

  def apply(implicit base: BaseUri): Encoder.AsObject[E]

}
