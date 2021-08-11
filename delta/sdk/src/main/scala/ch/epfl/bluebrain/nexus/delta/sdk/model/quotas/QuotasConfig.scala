package ch.epfl.bluebrain.nexus.delta.sdk.model.quotas

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.quotas.QuotasConfig.QuotaConfig
import pureconfig.ConfigReader
import pureconfig.configurable.genericMapReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.annotation.nowarn

/**
  * The configuration for quotas on projects
  *
  * @param resources maximum number of resources per project
  * @param events    maximum number of events per project
  * @param enabled   flag to enable or disable project quotas
  * @param custom    custom quotas for certain projects
  */
final case class QuotasConfig(
    private val resources: Option[Int],
    private val events: Option[Int],
    private val enabled: Boolean,
    private val custom: Map[ProjectRef, QuotaConfig]
) {
  def lookup(project: ProjectRef): Option[QuotaConfig] =
    Option.when(enabled)(custom.getOrElse(project, QuotaConfig(resources, events)))

}

object QuotasConfig {

  final private case class QuotasConfigInternal(
      resources: Option[Int],
      events: Option[Int],
      enabled: Boolean,
      custom: Map[ProjectRef, QuotaConfig]
  ) {
    def toQuotasConfig: QuotasConfig = QuotasConfig(resources, events, enabled, custom)
  }

  /**
    * The configuration for a single quota
    *
    * @param resources maximum number of resources per project
    * @param events    maximum number of events per project
    */
  final case class QuotaConfig(resources: Option[Int], events: Option[Int])

  private def validate(resources: Option[Int], events: Option[Int]) =
    (resources, events)
      .mapN {
        case (resources, events) if resources > events =>
          Left(
            CannotConvert(
              "resources",
              "QuotasConfig",
              s"resources '$resources' should be smaller or equal than events '$events'."
            )
          )
        case _                                         => Right(())
      }
      .getOrElse(Right(()))

  @nowarn("cat=unused")
  implicit final val quotasConfigReader: ConfigReader[QuotasConfig] = {
    implicit val quotaReader: ConfigReader[QuotaConfig]                      = deriveReader[QuotaConfig]
    implicit val customMapReader: ConfigReader[Map[ProjectRef, QuotaConfig]] =
      genericMapReader[ProjectRef, QuotaConfig] { key =>
        key.split("/").toList match {
          case orgStr :: projectStr :: Nil =>
            (Label(orgStr), Label(projectStr))
              .mapN(ProjectRef(_, _))
              .leftMap(err => CannotConvert(key, classOf[ProjectRef].getSimpleName, err.getMessage))
          case _                           =>
            Left(CannotConvert(key, classOf[ProjectRef].getSimpleName, "Wrong format"))
        }
      }

    deriveReader[QuotasConfigInternal].emap { config =>
      (QuotaConfig(config.resources, config.events) :: config.custom.values.toList).foldM(config.toQuotasConfig) {
        (config, entry) =>
          validate(entry.resources, entry.events).as(config)
      }
    }
  }
}
