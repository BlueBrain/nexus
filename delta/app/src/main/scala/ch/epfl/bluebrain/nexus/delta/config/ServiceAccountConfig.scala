package ch.epfl.bluebrain.nexus.delta.config

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Subject, User}
import pureconfig.ConfigReader
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}

/**
  * Configuration for the application service account
  * @param subject the subject to be used for internal operations
  */
final case class ServiceAccountConfig(subject: Subject)

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
            l => ServiceAccountConfig(User(subject, l))
          )
      } yield serviceAccountConfig
    }
}
