package ch.epfl.bluebrain.nexus.admin.organizations

import io.circe.{Encoder, Json}

/**
  * Representation of an organization.
  *
  * @param label        the label of the organization, used e.g. in the HTTP URLs and @id
  * @param description  an optional description of the organization
  */
final case class Organization(label: String, description: Option[String])

object Organization {

  implicit val organizationEncoder: Encoder[Organization] = Encoder.encodeJson.contramap {
    case Organization(label, Some(desc)) =>
      Json.obj("_label" -> Json.fromString(label), "description" -> Json.fromString(desc))
    case Organization(label, _) => Json.obj("_label" -> Json.fromString(label))
  }
}
