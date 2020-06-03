package ch.epfl.bluebrain.nexus.admin.projects

import java.util.UUID

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.{Encoder, Json}

/**
  * Type that represents a project.
  *
  * @param label             the project label (segment)
  * @param organizationUuid  the parent organization ID
  * @param organizationLabel the organization label
  * @param description       an optional description
  * @param apiMappings       the API mappings
  * @param base              the base IRI for generated resource IDs
  * @param vocab             an optional vocabulary for resources with no context
  */
final case class Project(
    label: String,
    organizationUuid: UUID,
    organizationLabel: String,
    description: Option[String],
    apiMappings: Map[String, AbsoluteIri],
    base: AbsoluteIri,
    vocab: AbsoluteIri
) {

  /**
    * @return full label for the project (including organization).
    */
  def fullLabel: String = s"$organizationLabel/$label"
}

object Project {

  implicit val projectEncoder: Encoder[Project] = Encoder.encodeJson.contramap { p =>
    Json
      .obj(
        "_label"             -> Json.fromString(p.label),
        "_organizationUuid"  -> Json.fromString(p.organizationUuid.toString),
        "_organizationLabel" -> Json.fromString(p.organizationLabel),
        "apiMappings" -> Json.arr(p.apiMappings.toList.map {
          case (prefix, namespace) =>
            Json.obj("prefix" -> Json.fromString(prefix), "namespace" -> Json.fromString(namespace.asString))
        }: _*),
        "base"  -> Json.fromString(p.base.asString),
        "vocab" -> Json.fromString(p.vocab.asString)
      )
      .deepMerge(p.description match {
        case Some(desc) => Json.obj("description" -> Json.fromString(desc))
        case None       => Json.obj()
      })
  }
}
