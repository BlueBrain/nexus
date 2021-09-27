package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.{Encoder, Json, JsonObject}
import pureconfig.ConfigReader
import pureconfig.error.CannotConvert
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration
import scala.util.matching.Regex

/**
  * Automatic Project Deletion configuration.
  *
  * @param idleInterval
  *   the interval after which a project is considered idle
  * @param idleCheckPeriod
  *   how often to check for idle projects
  * @param deleteDeprecatedProjects
  *   whether to delete deprecated projects immediately, without waiting for them to become idle
  * @param includedProjects
  *   a list of regexes that select which projects to be included in the idle check for automatic deletion
  * @param excludedProjects
  *   a list of regexes that select which projects to be excluded from the idle check for automatic deletion
  */
final case class ProjectDeletionConfig(
    idleInterval: FiniteDuration,
    idleCheckPeriod: FiniteDuration,
    deleteDeprecatedProjects: Boolean,
    includedProjects: List[Regex],
    excludedProjects: List[Regex]
)

object ProjectDeletionConfig {

  implicit final val projectDeletionConfigLdEncoder: JsonLdEncoder[ProjectDeletionConfig] = {
    implicit val jsonEncoder: Encoder.AsObject[ProjectDeletionConfig] =
      Encoder.encodeJsonObject.contramapObject { cfg =>
        JsonObject(
          keywords.tpe                -> Json.fromString("ProjectDeletionConfig"),
          "_idleIntervalInSeconds"    -> Json.fromLong(cfg.idleInterval.toSeconds),
          "_idleCheckPeriodInSeconds" -> Json.fromLong(cfg.idleCheckPeriod.toSeconds),
          "_deleteDeprecatedProjects" -> Json.fromBoolean(cfg.deleteDeprecatedProjects),
          "_includedProjects"         -> Json.arr(cfg.includedProjects.map(str => Json.fromString(str.regex)): _*),
          "_excludedProjects"         -> Json.arr(cfg.excludedProjects.map(str => Json.fromString(str.regex)): _*)
        )
      }

    JsonLdEncoder.computeFromCirce(ContextValue(contexts.projectDeletion))
  }

  implicit final val projectDeletionConfigReader: ConfigReader[ProjectDeletionConfig] =
    deriveReader[ProjectDeletionConfig].emap { cfg =>
      if (cfg.idleInterval.toMillis < cfg.idleCheckPeriod.toMillis)
        Left(
          CannotConvert(
            cfg.idleCheckPeriod.toString,
            classOf[FiniteDuration].getSimpleName,
            "'idle-interval' cannot be smaller than 'idle-check-period'"
          )
        )
      else Right(cfg)
    }
}
