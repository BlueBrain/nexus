package ai.senscience.nexus.delta.plugins.archive.model

import ai.senscience.nexus.delta.plugins.archive.model.Archive.Metadata
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.{Encoder, Json, JsonObject}

/**
  * An archive value with its ttl.
  *
  * @param id
  *   the archive id
  * @param project
  *   the archive parent project
  * @param resources
  *   the collection of resource references
  * @param expiresInSeconds
  *   the archive ttl
  */
final case class Archive(
    id: Iri,
    project: ProjectRef,
    resources: NonEmptySet[ArchiveReference],
    expiresInSeconds: Long
) {

  /**
    * @return
    *   the corresponding archive value
    */
  def value: ArchiveValue =
    ArchiveValue.unsafe(resources) // safe because an archive is only produced from a state

  /**
    * @return
    *   the archive metadata
    */
  def metadata: Metadata =
    Metadata(project, expiresInSeconds)
}

object Archive {

  /**
    * Additional archive metadata.
    *
    * @param project
    *   the parent archive project
    * @param expiresInSeconds
    *   the period in seconds after which an archive is expired
    */
  final case class Metadata(project: ProjectRef, expiresInSeconds: Long)

  object Metadata {
    implicit private val archiveMetadataEncoder: Encoder.AsObject[Metadata] =
      Encoder.encodeJsonObject.contramapObject { meta =>
        JsonObject("_expiresInSeconds" -> Json.fromLong(meta.expiresInSeconds))
      }

    implicit val archiveMetadataJsonLdEncoder: JsonLdEncoder[Metadata] =
      JsonLdEncoder.computeFromCirce(ContextValue(contexts.archivesMetadata))
  }

  implicit private def archiveJsonEncoder: Encoder.AsObject[Archive] = {
    import io.circe.generic.extras.semiauto.*
    implicit val cfg: Configuration = Configuration.default.copy(transformMemberNames = {
      case "expiresInSeconds" => "_expiresInSeconds"
      case other              => other
    })
    val archiveEncoder              = deriveConfiguredEncoder[Archive]
    Encoder.encodeJsonObject.contramapObject { v =>
      archiveEncoder.encodeObject(v).remove("id").remove("project")
    }
  }

  implicit val archiveJsonLdEncoder: JsonLdEncoder[Archive] =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.archives))

}
