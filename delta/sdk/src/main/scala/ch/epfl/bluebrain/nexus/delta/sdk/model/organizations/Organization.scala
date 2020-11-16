package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
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
}

object Organization {

  implicit private[Organization] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "label" => nxv.label.prefix
    case "uuid"  => nxv.uuid.prefix
    case other   => other
  })

  implicit val organizationEncoder: Encoder.AsObject[Organization] =
    deriveConfiguredEncoder[Organization]

  val context: ContextValue                                           = ContextValue(contexts.organizations)
  implicit val organizationJsonLdEncoder: JsonLdEncoder[Organization] =
    JsonLdEncoder.fromCirce(context)
}
