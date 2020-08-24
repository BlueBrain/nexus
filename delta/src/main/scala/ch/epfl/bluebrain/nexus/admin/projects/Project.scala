package ch.epfl.bluebrain.nexus.admin.projects

import java.util.UUID

import cats.Show
import cats.implicits._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectLabel
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Encoder, Json}

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
    * @return label for the project (including organization).
    */
  def projectLabel: ProjectLabel = ProjectLabel(organizationLabel, label)

  /**
    * @return the project path
    */
  def path: Path = organizationLabel / label
}

object Project {

  implicit val projectShow: Show[Project] = Show.show(project => project.projectLabel.show)

  implicit val projectEncoder: Encoder[Project] = Encoder.encodeJson.contramap { p =>
    Json
      .obj(
        "_label"             -> Json.fromString(p.label),
        "_organizationUuid"  -> Json.fromString(p.organizationUuid.toString),
        "_organizationLabel" -> Json.fromString(p.organizationLabel),
        "apiMappings"        -> Json.arr(p.apiMappings.toList.map {
          case (prefix, namespace) =>
            Json.obj("prefix" -> Json.fromString(prefix), "namespace" -> Json.fromString(namespace.asString))
        }: _*),
        "base"               -> Json.fromString(p.base.asString),
        "vocab"              -> Json.fromString(p.vocab.asString)
      )
      .deepMerge(p.description match {
        case Some(desc) => Json.obj("description" -> Json.fromString(desc))
        case None       => Json.obj()
      })
  }

  final private[projects] case class ApiMappings(prefix: String, namespace: AbsoluteIri)
  implicit private[projects] val apiMappingsDecoder: Decoder[ApiMappings] = deriveDecoder[ApiMappings]

  implicit val projectDecoder: Decoder[Project] = Decoder.instance { hc =>
    for {
      label             <- hc.get[String]("_label")
      organizationUuid  <- hc.get[UUID]("_organizationUuid")
      organizationLabel <- hc.get[String]("_organizationLabel")
      description       <- hc.get[Option[String]]("description")
      base              <- hc.get[AbsoluteIri]("base")
      vocab             <- hc.get[AbsoluteIri]("vocab")
      apiMappingList    <- hc.get[List[ApiMappings]]("apiMappings")
      apiMappings        = apiMappingList.map(v => v.prefix -> v.namespace).toMap
    } yield Project(label, organizationUuid, organizationLabel, description, apiMappings, base, vocab)

  }
}
