package ch.epfl.bluebrain.nexus.delta.config

import akka.http.scaladsl.model.Uri
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.identity.GroupsConfig
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig._
import ch.epfl.bluebrain.nexus.sourcing.processor.AggregateConfig
import monix.execution.Scheduler
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto._

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * Common ConfigReader instances for types that are not defined in the app module.
  */
trait ConfigReaderInstances {

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] =
    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .map(uri => BaseUri(uri))
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

  final private val onceRetryStrategyConfigReader: ConfigReader[OnceStrategyConfig] =
    deriveReader[OnceStrategyConfig]

  final private val constantRetryStrategyConfigReader: ConfigReader[ConstantStrategyConfig] =
    deriveReader[ConstantStrategyConfig]

  final private val exponentialRetryStrategyConfigReader: ConfigReader[ExponentialStrategyConfig] =
    deriveReader[ExponentialStrategyConfig]

  implicit final val retryStrategyConfigReader: ConfigReader[RetryStrategyConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj      <- cursor.asObjectCursor
        rc       <- obj.atKey("retry")
        retry    <- ConfigReader[String].from(rc)
        strategy <- retry match {
                      case "never"       => Right(AlwaysGiveUp)
                      case "once"        => onceRetryStrategyConfigReader.from(obj)
                      case "constant"    => constantRetryStrategyConfigReader.from(obj)
                      case "exponential" => exponentialRetryStrategyConfigReader.from(obj)
                      case other         =>
                        Left(
                          ConfigReaderFailures(
                            ConvertFailure(
                              CannotConvert(
                                other,
                                "string",
                                "'retry' value must be one of ('never', 'once', 'constant', 'exponential')"
                              ),
                              obj
                            )
                          )
                        )
                    }
      } yield strategy
    }

  implicit final val groupsConfigReader: ConfigReader[GroupsConfig] =
    deriveReader[GroupsConfig]

  implicit final val keyValueStoreConfigReader: ConfigReader[KeyValueStoreConfig] =
    deriveReader[KeyValueStoreConfig]

  implicit final val realmsConfigReader: ConfigReader[RealmsConfig] =
    deriveReader[RealmsConfig]

}

object ConfigReaderInstances extends ConfigReaderInstances
