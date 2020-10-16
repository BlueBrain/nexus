package ch.epfl.bluebrain.nexus.delta.config

import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import monix.execution.Scheduler
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto._

import scala.concurrent.duration.FiniteDuration

/**
  * The permissions module config.
  * @param minimum   the minimum collection of permissions
  * @param aggregate the aggregate config
  */
final case class PermissionsConfig(
    minimum: Set[Permission],
    aggregate: AggregateConfig
)

object PermissionsConfig {

  implicit final val permissionConfigReader: ConfigReader[Permission] =
    ConfigReader.fromString(str =>
      Permission(str).leftMap(err => CannotConvert(str, classOf[Permission].getSimpleName, err.getMessage))
    )

  implicit final val aggregateConfigReader: ConfigReader[AggregateConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj                   <- cursor.asObjectCursor
        atc                   <- obj.atKey("ask-timeout")
        askTimeout            <- ConfigReader[FiniteDuration].from(atc)
        emdc                  <- obj.atKey("evaluation-max-duration")
        evaluationMaxDuration <- ConfigReader[FiniteDuration].from(emdc)
        ssc                   <- obj.atKey("stash-size")
        stashSize             <- ssc.asInt
      } yield AggregateConfig(Timeout(askTimeout), evaluationMaxDuration, Scheduler.global, stashSize)
    }

  implicit final val permissionsConfigReader: ConfigReader[PermissionsConfig] =
    deriveReader

}
