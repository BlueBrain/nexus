package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization.Metadata
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

/**
  * Representation of an organization.
  *
  * @param label        the label of the organization
  * @param uuid         the UUID of the organization
  * @param description  an optional description of the organization
  */
final case class Organization(label: Label, uuid: UUID, description: Option[String]) {
  override def toString: String = label.toString

  /**
    * @return [[Organization]] metadata
    */
  def metadata: Metadata = Metadata(label, uuid)
}

object Organization {

  /**
    * Organization metadata.
    *
    * @param label  the label of the organization
    * @param uuid   the UUID of the organization
    */
  final case class Metadata(label: Label, uuid: UUID)

  implicit private[Organization] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "label" => nxv.label.prefix
    case "uuid"  => nxv.uuid.prefix
    case other   => other
  })

  implicit val organizationEncoder: Encoder.AsObject[Organization] =
    deriveConfiguredEncoder[Organization]

  val context: ContextValue                                           = ContextValue(contexts.organizations)
  implicit val organizationJsonLdEncoder: JsonLdEncoder[Organization] =
    JsonLdEncoder.computeFromCirce(context)

  implicit private val organizationMetadataEncoder: Encoder.AsObject[Metadata] = deriveConfiguredEncoder[Metadata]
  implicit val organizationMetadataJsonLdEncoder: JsonLdEncoder[Metadata]      =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))
}
