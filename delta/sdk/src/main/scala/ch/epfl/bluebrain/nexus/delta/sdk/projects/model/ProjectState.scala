package ch.epfl.bluebrain.nexus.delta.sdk.projects.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectResource
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.ScopedState
import io.circe.{Codec, Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * State used for all resources that have been created and later possibly updated or deprecated.
  *
  * @param label
  *   the project label
  * @param uuid
  *   the project unique identifier
  * @param organizationLabel
  *   the parent organization label
  * @param organizationUuid
  *   the parent organization uuid
  * @param rev
  *   the current state revision
  * @param deprecated
  *   the current state deprecation status
  * @param markedForDeletion
  *   the current marked for deletion status
  * @param description
  *   an optional project description
  * @param apiMappings
  *   the project API mappings
  * @param base
  *   the base Iri for generated resource IDs
  * @param vocab
  *   an optional vocabulary for resources with no context
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the subject that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the subject that last updated the resource
  */
final case class ProjectState(
    label: Label,
    uuid: UUID,
    organizationLabel: Label,
    organizationUuid: UUID,
    rev: Int,
    deprecated: Boolean,
    markedForDeletion: Boolean,
    description: Option[String],
    apiMappings: ApiMappings,
    base: ProjectBase,
    vocab: Iri,
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends ScopedState {

  override val project: ProjectRef = ProjectRef(organizationLabel, label)

  private val uris = ResourceUris.project(project)

  /**
    * @return
    *   the schema reference that projects conforms to
    */
  def schema: ResourceRef = Latest(schemas.projects)

  /**
    * @return
    *   the collection of known types of projects resources
    */
  def types: Set[Iri] = Set(nxv.Project)

  /**
    * Converts the state into a resource representation.
    */
  def toResource(defaultApiMappings: ApiMappings): ProjectResource =
    ResourceF(
      id = uris.relativeAccessUri.toIri,
      uris = uris,
      rev = rev.toLong,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = Project(
        label,
        uuid,
        organizationLabel,
        organizationUuid,
        description,
        apiMappings,
        defaultApiMappings,
        base,
        vocab,
        markedForDeletion
      )
    )
}

object ProjectState {

  @nowarn("cat=unused")
  implicit val serializer: Serializer[ProjectRef, ProjectState] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration = Serializer.circeConfiguration

    implicit val apiMappingsDecoder: Decoder[ApiMappings]          =
      Decoder.decodeMap[String, Iri].map(ApiMappings(_))
    implicit val apiMappingsEncoder: Encoder.AsObject[ApiMappings] =
      Encoder.encodeMap[String, Iri].contramapObject(_.value)

    implicit val coder: Codec.AsObject[ProjectState] = deriveConfiguredCodec[ProjectState]
    Serializer(_.project)
  }

}
