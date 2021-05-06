package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project.{Metadata, Source}
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.EncoderOps

import java.util.UUID

/**
  * A project representation.
  *
  * @param label             the project label
  * @param uuid              the project unique identifier
  * @param organizationLabel the parent organization label
  * @param organizationUuid  the parent organization unique identifier
  * @param description       an optional description
  * @param apiMappings       the API mappings
  * @param base              the base Iri for generated resource IDs
  * @param vocab             an optional vocabulary for resources with no context
  */
final case class Project(
    label: Label,
    uuid: UUID,
    organizationLabel: Label,
    organizationUuid: UUID,
    description: Option[String],
    apiMappings: ApiMappings,
    base: ProjectBase,
    vocab: Iri
) {

  /**
    * @return a project label reference containing the parent organization label
    */
  def ref: ProjectRef =
    ProjectRef(organizationLabel, label)

  /**
    * @return [[Project]] metadata
    */
  def metadata: Metadata = Metadata(label, uuid, organizationLabel, organizationUuid, apiMappings)

  /**
    * @return the [[Project]] source
    */
  def source: Source = Source(description, apiMappings, base, vocab)

}

object Project {

  /**
    * Project metadata.
    *
    * @see [[Project]]
    */
  final case class Metadata(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      effectiveApiMappings: ApiMappings
  )

  /**
    * Project source.
    *
    * @see [[Project]]
    */
  final case class Source(description: Option[String], apiMappings: ApiMappings, base: ProjectBase, vocab: Iri)

  object Source {
    implicit val projectSourceEncoder: Encoder[Source] = deriveEncoder[Source]
  }

  val context: ContextValue = ContextValue(contexts.projects)

  implicit private[Project] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "label"                => nxv.label.prefix
    case "uuid"                 => nxv.uuid.prefix
    case "organizationLabel"    => nxv.organizationLabel.prefix
    case "organizationUuid"     => nxv.organizationUuid.prefix
    case "effectiveApiMappings" => nxv.effectiveApiMappings.prefix
    case other                  => other
  })

  implicit def projectEncoder(implicit defaultApiMappings: ApiMappings): Encoder.AsObject[Project] =
    Encoder.encodeJsonObject.contramapObject { project =>
      deriveConfiguredEncoder[Project]
        .encodeObject(project)
        .add("apiMappings", (project.apiMappings - defaultApiMappings).asJson)
        .add(nxv.effectiveApiMappings.prefix, project.apiMappings.asJson)
    }

  implicit def projectJsonLdEncoder(implicit defaultMappings: ApiMappings): JsonLdEncoder[Project] =
    JsonLdEncoder.computeFromCirce(context)

  implicit private val projectMetadataEncoder: Encoder.AsObject[Metadata] = deriveConfiguredEncoder[Metadata]
  implicit val projectMetadataJsonLdEncoder: JsonLdEncoder[Metadata]      =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.projectsMetadata))

}
