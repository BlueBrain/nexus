package ch.epfl.bluebrain.nexus.cli.config.literature

import java.nio.file.Path

import ch.epfl.bluebrain.nexus.cli.config.PrintConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, ProjectLabel}
import pureconfig.{ConfigConvert, ConfigReader, ConfigWriter}
import pureconfig.configurable.{genericMapReader, genericMapWriter}
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveConvert

import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * Literature extraction configuration
  *
  * @param offsetFile         the location where to store the literature of the projection
  * @param offsetSaveInterval the how frequent to save the current offset to a file
  * @param blueBrainSearch    the Blue Brain Search configuration, used for calculating vector embeddings
  * @param elasticSearch      the ElasticSearch configuration, used to project data
  * @param print              the configuration for printing output to the client
  * @param projects           the projects configuration, used to consume projects from SSE
  */
final case class LiteratureConfig(
    offsetFile: Path,
    offsetSaveInterval: FiniteDuration,
    blueBrainSearch: BlueBrainSearchConfig,
    elasticSearch: ElasticSearchLiteratureConfig,
    print: PrintConfig,
    projects: Map[(OrgLabel, ProjectLabel), ProjectConfig]
)

object LiteratureConfig {

  @nowarn("cat=unused")
  implicit final val literatureConfigConvert: ConfigConvert[LiteratureConfig] = {
    implicit val labelTupleMapReader: ConfigReader[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapReader[(OrgLabel, ProjectLabel), ProjectConfig] { key =>
        key.split('/') match {
          case Array(org, proj) if !org.isBlank && !proj.isBlank => Right(OrgLabel(org.trim) -> ProjectLabel(proj.trim))
          case _                                                 => Left(CannotConvert(key, "project reference", "the format does not match {org}/{project}"))
        }
      }
    implicit val labelTupleMapWriter: ConfigWriter[Map[(OrgLabel, ProjectLabel), ProjectConfig]] =
      genericMapWriter { case (OrgLabel(org), ProjectLabel(proj)) => s"$org/$proj" }

    deriveConvert[LiteratureConfig]
  }
}
