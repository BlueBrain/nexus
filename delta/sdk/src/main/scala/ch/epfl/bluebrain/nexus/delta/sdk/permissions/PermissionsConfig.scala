package ch.epfl.bluebrain.nexus.delta.sdk.permissions

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

/**
  * The permissions module config.
  * @param eventLog
  *   The event log configuration
  * @param minimum
  *   the minimum collection of permissions
  */
final case class PermissionsConfig(
    eventLog: EventLogConfig,
    minimum: Set[Permission],
    ownerPermissions: Set[Permission]
)

object PermissionsConfig {

  implicit final val permissionConfigReader: ConfigReader[Permission] =
    ConfigReader.fromString(str =>
      Permission(str).leftMap(err => CannotConvert(str, classOf[Permission].getSimpleName, err.getMessage))
    )

  implicit final val permissionsConfigReader: ConfigReader[PermissionsConfig] =
    deriveReader[PermissionsConfig]

}
