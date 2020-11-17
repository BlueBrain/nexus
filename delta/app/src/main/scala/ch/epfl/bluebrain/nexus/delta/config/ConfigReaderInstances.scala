package ch.epfl.bluebrain.nexus.delta.config

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.util.Timeout
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.service.acls.AclsConfig
import ch.epfl.bluebrain.nexus.delta.service.cache.KeyValueStoreConfig
import ch.epfl.bluebrain.nexus.delta.service.config.{AggregateConfig, IndexingConfig}
import ch.epfl.bluebrain.nexus.delta.service.identity.GroupsConfig
import ch.epfl.bluebrain.nexus.delta.service.organizations.OrganizationsConfig
import ch.epfl.bluebrain.nexus.delta.service.projects.ProjectsConfig
import ch.epfl.bluebrain.nexus.delta.service.realms.RealmsConfig
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig._
import ch.epfl.bluebrain.nexus.sourcing.processor.{EventSourceProcessorConfig, StopStrategyConfig}
import ch.epfl.bluebrain.nexus.sourcing.{RetryStrategy, RetryStrategyConfig, SnapshotStrategyConfig}
import com.typesafe.scalalogging.Logger
import monix.execution.Scheduler
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}
import pureconfig.generic.semiauto._

import scala.annotation.{nowarn, tailrec}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * Common ConfigReader instances for types that are not defined in the app module.
  */
trait ConfigReaderInstances {

  implicit final val baseUriConfigReader: ConfigReader[BaseUri] = {
    @tailrec
    def rec(uri: Uri, consumed: Path, remaining: Path): Either[CannotConvert, BaseUri] = remaining match {
      case Path.Empty                                              => Right(BaseUri(uri.withPath(consumed)))
      case Path.Slash(tail)                                        => rec(uri, consumed, tail)
      case Path.Segment(head, Path.Slash(Path.Empty) | Path.Empty) =>
        Label(head)
          .leftMap(err => CannotConvert(head, classOf[Label].getSimpleName, err.getMessage))
          .map(label => BaseUri(uri.withPath(consumed).withoutFragment.copy(rawQueryString = None), Some(label)))
      case Path.Segment(head, Path.Slash(Path.Slash(other)))       =>
        rec(uri, consumed, Path.Segment(head, Path.Slash(other)))
      case Path.Segment(head, Path.Slash(other))                   =>
        rec(uri, consumed ?/ head, other)
    }

    ConfigReader.fromString(str =>
      Try(Uri(str)).toEither
        .leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err.getMessage))
        .flatMap { uri =>
          if (uri.isAbsolute) rec(uri, Path.Empty, uri.path)
          else Left(CannotConvert(str, classOf[Uri].getSimpleName, "The value must be an absolute Uri."))
        }
    )
  }

  implicit final val serviceAccountConfigReader: ConfigReader[ServiceAccountConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj                  <- cursor.asObjectCursor
        subjectK             <- obj.atKey("subject")
        subject              <- ConfigReader[String].from(subjectK)
        realmK               <- obj.atKey("realm")
        realm                <- ConfigReader[String].from(realmK)
        serviceAccountConfig <-
          Label(realm).bimap(
            e =>
              ConfigReaderFailures(
                ConvertFailure(
                  CannotConvert("serviceAccountConfig", "ServiceAccountConfig", e.getMessage),
                  obj
                )
              ),
            l => ServiceAccountConfig(User(subject, l))
          )
      } yield serviceAccountConfig
    }

  implicit final val stopStrategyConfigReader: ConfigReader[StopStrategyConfig] =
    deriveReader[StopStrategyConfig]

  implicit final val snapshotStrategyConfigReader: ConfigReader[SnapshotStrategyConfig] =
    ConfigReader.fromCursor { cursor =>
      for {
        obj            <- cursor.asObjectCursor
        noeK           <- obj.atKey("number-of-events")
        noe            <- ConfigReader[Option[Int]].from(noeK)
        keepK          <- obj.atKey("keep-snapshots")
        keep           <- ConfigReader[Option[Int]].from(keepK)
        deleteK        <- obj.atKey("delete-events-on-snapshot")
        delete         <- ConfigReader[Option[Boolean]].from(deleteK)
        snapshotConfig <-
          SnapshotStrategyConfig(noe, keep, delete).toRight(
            ConfigReaderFailures(
              ConvertFailure(
                CannotConvert("snapshotConfig", "SnapshotStrategyConfig", "All it's members must exist or be empty"),
                obj
              )
            )
          )
      } yield snapshotConfig
    }

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
        stopStrategyK         <- obj.atKey("stop-strategy")
        stopStrategy          <- ConfigReader[StopStrategyConfig].from(stopStrategyK)
        snapshotStrategyK     <- obj.atKey("snapshot-strategy")
        snapshotStrategy      <- ConfigReader[SnapshotStrategyConfig].from(snapshotStrategyK)
      } yield AggregateConfig(
        stopStrategy,
        snapshotStrategy,
        EventSourceProcessorConfig(Timeout(askTimeout), evaluationMaxDuration, Scheduler.global, stashSize)
      )
    }

  implicit final val retryStrategyConfigReader: ConfigReader[RetryStrategyConfig] = {
    val onceRetryStrategy: ConfigReader[OnceStrategyConfig]               = deriveReader[OnceStrategyConfig]
    val constantRetryStrategy: ConfigReader[ConstantStrategyConfig]       = deriveReader[ConstantStrategyConfig]
    val exponentialRetryStrategy: ConfigReader[ExponentialStrategyConfig] = deriveReader[ExponentialStrategyConfig]

    ConfigReader.fromCursor { cursor =>
      for {
        obj      <- cursor.asObjectCursor
        rc       <- obj.atKey("retry")
        retry    <- ConfigReader[String].from(rc)
        strategy <- retry match {
                      case "never"       => Right(AlwaysGiveUp)
                      case "once"        => onceRetryStrategy.from(obj)
                      case "constant"    => constantRetryStrategy.from(obj)
                      case "exponential" => exponentialRetryStrategy.from(obj)
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
  }

  implicit final val indexingConfigReader: ConfigReader[IndexingConfig] = {
    val logger: Logger = Logger[IndexingConfig]

    @nowarn("cat=unused")
    implicit val retryStrategyConfig: ConfigReader[RetryStrategy] =
      retryStrategyConfigReader.map(config =>
        RetryStrategy(config, _ => false, RetryStrategy.logError(logger, "indexing"))
      )

    deriveReader[IndexingConfig]
  }

  implicit final val groupsConfigReader: ConfigReader[GroupsConfig] =
    deriveReader[GroupsConfig]

  implicit final val keyValueStoreConfigReader: ConfigReader[KeyValueStoreConfig] =
    deriveReader[KeyValueStoreConfig]

  implicit final val paginationConfigReader: ConfigReader[PaginationConfig] =
    deriveReader[PaginationConfig]

  implicit final val realmsConfigReader: ConfigReader[RealmsConfig] =
    deriveReader[RealmsConfig]

  implicit final val aclsConfigReader: ConfigReader[AclsConfig] =
    deriveReader[AclsConfig]

  implicit final val orgsConfigReader: ConfigReader[OrganizationsConfig] =
    deriveReader[OrganizationsConfig]

  implicit final val projectConfigReader: ConfigReader[ProjectsConfig] =
    deriveReader[ProjectsConfig]

  implicit final val resourcesConfigReader: ConfigReader[ResourcesConfig] =
    deriveReader[ResourcesConfig]

}

object ConfigReaderInstances extends ConfigReaderInstances
