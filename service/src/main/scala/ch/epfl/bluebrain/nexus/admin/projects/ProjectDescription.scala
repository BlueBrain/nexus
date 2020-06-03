package ch.epfl.bluebrain.nexus.admin.projects

import ch.epfl.bluebrain.nexus.admin.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.{Decoder, DecodingFailure}

/**
  * Type that represents a project payload for creation and update requests.
  *
  * @param description an optional description
  * @param apiMappings the API mappings
  * @param base        an optional base IRI for generated resource IDs
  * @param vocab       an optional vocabulary for resources with no context
  */
final case class ProjectDescription(
    description: Option[String],
    apiMappings: Map[String, AbsoluteIri],
    base: Option[AbsoluteIri],
    vocab: Option[AbsoluteIri]
) {

  /**
    * @return the current base or a generated one based on the ''http.prefixIri'', ''organization'' and ''label''
    */
  def baseOrGenerated(organization: String, label: String)(implicit http: HttpConfig): Option[AbsoluteIri] =
    base orElse Iri.absolute(s"${http.prefixIri.asString}/resources/$organization/$label/_/").toOption

  /**
    * @return the current vocab or a generated one based on the ''http.prefixIri'', ''organization'' and ''label''
    */
  def vocabOrGenerated(organization: String, label: String)(implicit http: HttpConfig): Option[AbsoluteIri] =
    vocab orElse Iri.absolute(s"${http.prefixIri.asString}/vocabs/$organization/$label/").toOption
}

object ProjectDescription {

  final case class Mapping(prefix: String, namespace: AbsoluteIri)

  implicit val mappingDecoder: Decoder[Mapping] = Decoder.instance { hc =>
    for {
      prefix    <- hc.downField("prefix").as[String]
      namespace <- hc.downField("namespace").as[String]
      iri       <- Iri.absolute(namespace).left.map(err => DecodingFailure(err, hc.history))
    } yield Mapping(prefix, iri)
  }

  implicit val descriptionDecoder: Decoder[ProjectDescription] = Decoder.instance { hc =>
    for {
      desc <- hc.downField("description").as[Option[String]]
      lam = hc.downField("apiMappings").as[List[Mapping]].getOrElse(List.empty)
      map = lam.map(am => am.prefix -> am.namespace).toMap
      base <- hc.downField("base").as[Option[AbsoluteIri]]
      voc  <- hc.downField("vocab").as[Option[AbsoluteIri]]
    } yield ProjectDescription(desc, map, base, voc)
  }
}
