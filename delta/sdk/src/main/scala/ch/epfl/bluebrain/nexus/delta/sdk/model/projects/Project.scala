package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

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
}

object Project {

  val context: ContextValue = ContextValue(contexts.projects)

  implicit private[Project] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "label"             => nxv.label.prefix
    case "uuid"              => nxv.uuid.prefix
    case "organizationLabel" => nxv.organizationLabel.prefix
    case "organizationUuid"  => nxv.organizationUuid.prefix
    case other               => other
  })

  implicit val projectEncoder: Encoder.AsObject[Project]    = deriveConfiguredEncoder[Project]
  implicit val projectJsonLdEncoder: JsonLdEncoder[Project] =
    JsonLdEncoder.compactFromCirce(context)

}
