package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

import scala.annotation.nowarn

final case class ExportEventQuery(id: Label, projects: NonEmptyList[ProjectRef], offset: Offset)

object ExportEventQuery {

  @nowarn("cat=unused")
  implicit private val config: Configuration                 = Configuration.default.withStrictDecoding
  implicit val exportQueryDecoder: Decoder[ExportEventQuery] = deriveConfiguredDecoder[ExportEventQuery]
}
