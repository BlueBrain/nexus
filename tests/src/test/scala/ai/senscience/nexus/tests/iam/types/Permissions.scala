package ai.senscience.nexus.tests.iam.types

import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredDecoder

final case class Permissions(permissions: Set[Permission], _rev: Int)

object Permissions {

  implicit val config: Configuration = Configuration.default

  implicit val identityDecoder: Decoder[Permissions] = {
    deriveConfiguredDecoder[Permissions]
  }
}
