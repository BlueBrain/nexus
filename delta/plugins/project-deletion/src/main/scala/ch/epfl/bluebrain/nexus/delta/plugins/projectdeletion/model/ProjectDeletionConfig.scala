package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion.model

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe.{Encoder, Json, JsonObject}
import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

import scala.concurrent.duration.FiniteDuration

final case class ProjectDeletionConfig(
    idleInterval: FiniteDuration,
    idleCheckPeriod: FiniteDuration,
    deleteDeprecatedProjects: Boolean
)

object ProjectDeletionConfig {

  implicit final val projectDeletionConfig: JsonLdEncoder[ProjectDeletionConfig] = {
    implicit val jsonEncoder: Encoder.AsObject[ProjectDeletionConfig] =
      Encoder.encodeJsonObject.contramapObject { cfg =>
        JsonObject(
          keywords.tpe                -> Json.fromString("ProjectDeletionConfig"),
          "_idleIntervalInSeconds"    -> Json.fromLong(cfg.idleInterval.toSeconds),
          "_idleCheckPeriodInSeconds" -> Json.fromLong(cfg.idleCheckPeriod.toSeconds),
          "_deleteDeprecatedProjects" -> Json.fromBoolean(cfg.deleteDeprecatedProjects)
        )
      }

    JsonLdEncoder.computeFromCirce(ContextValue(contexts.projectDeletion))
  }

  implicit final val archivePluginConfigReader: ConfigReader[ProjectDeletionConfig] =
    deriveReader[ProjectDeletionConfig]
}
