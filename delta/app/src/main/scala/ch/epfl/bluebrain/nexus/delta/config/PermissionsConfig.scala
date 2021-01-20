package ch.epfl.bluebrain.nexus.delta.config

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.sourcing.config.AggregateConfig
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

/**
  * The permissions module config.
  * @param minimum   the minimum collection of permissions
  * @param aggregate the aggregate config
  */
final case class PermissionsConfig(
    minimum: Set[Permission],
    ownerPermissions: Set[Permission],
    aggregate: AggregateConfig
)

object PermissionsConfig {

  implicit final val permissionConfigReader: ConfigReader[Permission] =
    ConfigReader.fromString(str =>
      Permission(str).leftMap(err => CannotConvert(str, classOf[Permission].getSimpleName, err.getMessage))
    )

  implicit final val permissionsConfigReader: ConfigReader[PermissionsConfig] =
    deriveReader[PermissionsConfig]

}
