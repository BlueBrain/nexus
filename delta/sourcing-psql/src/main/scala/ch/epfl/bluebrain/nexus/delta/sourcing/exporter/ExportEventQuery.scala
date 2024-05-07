package ch.epfl.bluebrain.nexus.delta.sourcing.exporter

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import io.circe.Codec
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

final case class ExportEventQuery(output: Label, projects: NonEmptyList[ProjectRef], offset: Offset)

object ExportEventQuery {

  implicit private val config: Configuration             = Configuration.default.withStrictDecoding
  implicit val exportQueryCodec: Codec[ExportEventQuery] = deriveConfiguredCodec[ExportEventQuery]
}
