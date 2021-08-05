package ch.epfl.bluebrain.nexus.delta.sdk.model.quotas

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * The configuration for quotas on projects
  *
  * @param resources maximum number of resources per project
  * @param enabled   flag to enable or disable project quotas
  * @param custom    custom quotas for certain projects
  */
final case class QuotasConfig(resources: Int, enabled: Boolean, custom: Map[ProjectRef, Int])

object QuotasConfig {

  @nowarn("cat=unused")
  implicit final val quotasConfigReader: ConfigReader[QuotasConfig] = {

    implicit val customMapReader: ConfigReader[Map[ProjectRef, Int]] = genericMapReader[ProjectRef, Int] { key =>
      key.split("/").toList match {
        case orgStr :: projectStr :: Nil =>
          (Label(orgStr), Label(projectStr))
            .mapN(ProjectRef(_, _))
            .leftMap(err => CannotConvert(key, classOf[ProjectRef].getSimpleName, err.getMessage))
        case _                           =>
          Left(CannotConvert(key, classOf[ProjectRef].getSimpleName, "Wrong format"))
      }
    }

    deriveReader[QuotasConfig]
  }
}
