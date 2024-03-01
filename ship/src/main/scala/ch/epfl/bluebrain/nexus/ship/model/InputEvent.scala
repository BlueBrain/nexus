package ch.epfl.bluebrain.nexus.ship.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.{Decoder, Json}

import java.time.Instant
import scala.annotation.nowarn

final case class InputEvent(
    ordering: Offset.At,
    `type`: EntityType,
    org: Label,
    project: Label,
    id: Iri,
    rev: Int,
    value: Json,
    instant: Instant
)

object InputEvent {

  @nowarn("cat=unused")
  implicit final val elasticSearchViewValueEncoder: Decoder[InputEvent] = {
    import io.circe.generic.extras.Configuration
    import io.circe.generic.extras.semiauto.deriveConfiguredDecoder
    implicit val offsetDecoder: Decoder[Offset.At] = Decoder.decodeLong.map(Offset.At)
    implicit val config: Configuration             = Configuration.default
    deriveConfiguredDecoder[InputEvent]
  }
}
