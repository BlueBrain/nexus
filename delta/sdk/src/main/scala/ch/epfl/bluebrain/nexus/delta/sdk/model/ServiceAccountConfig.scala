package ch.epfl.bluebrain.nexus.delta.sdk.model

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.ServiceAccount
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}

/**
  * Configuration for the application service account.
  *
  * @param value
  *   the service account to be used for internal operations
  */
final case class ServiceAccountConfig(value: ServiceAccount)

object ServiceAccountConfig {
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
            l => ServiceAccountConfig(ServiceAccount(User(subject, l)))
          )
      } yield serviceAccountConfig
    }
}
