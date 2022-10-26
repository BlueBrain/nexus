package ch.epfl.bluebrain.nexus.delta.plugins.archive.model

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectBase}
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.EphemeralState
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredDecoder, deriveConfiguredEncoder}

import java.time.Instant
import scala.annotation.nowarn
import scala.concurrent.duration.FiniteDuration

/**
  * State of an existing archive.
  *
  * @param id
  *   the archive identifier
  * @param project
  *   the archive parent project
  * @param resources
  *   the collection of referenced resources
  * @param createdAt
  *   the instant when the archive was created
  * @param createdBy
  *   the subject that created the archive
  */
final case class ArchiveState(
    id: Iri,
    project: ProjectRef,
    resources: NonEmptySet[ArchiveReference],
    createdAt: Instant,
    createdBy: Subject
) extends EphemeralState {

  override def schema: ResourceRef = model.schema

  override def types: Set[Iri] = Set(tpe)

  def toResource(mappings: ApiMappings, base: ProjectBase, ttl: FiniteDuration): ArchiveResource =
    ResourceF(
      id = id,
      uris = ResourceUris.ephemeral("archives", project, id)(mappings, base),
      rev = this.rev.toLong,
      types = this.types,
      deprecated = this.deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = createdAt,
      updatedBy = createdBy,
      schema = this.schema,
      value = Archive(id, project, resources, ttl.toSeconds)
    )
}

object ArchiveState {

  @nowarn("cat=unused")
  implicit val serializer: Serializer[Iri, ArchiveState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration                                                 = Serializer.circeConfiguration
    implicit val archiveResourceRepresentation: Codec.AsObject[ArchiveResourceRepresentation] =
      deriveConfiguredCodec[ArchiveResourceRepresentation]
    implicit val archiveReferenceCodec: Codec.AsObject[ArchiveReference]                      = deriveConfiguredCodec[ArchiveReference]
    implicit val archiveValueEncoder: Encoder[ArchiveValue]                                   =
      deriveConfiguredEncoder[ArchiveValue].mapJson(_.deepDropNullValues)
    implicit val archiveValueDecoder: Decoder[ArchiveValue]                                   = deriveConfiguredDecoder[ArchiveValue]
    implicit val codec: Codec.AsObject[ArchiveState]                                          = deriveConfiguredCodec[ArchiveState]
    Serializer(_.id)
  }

}
