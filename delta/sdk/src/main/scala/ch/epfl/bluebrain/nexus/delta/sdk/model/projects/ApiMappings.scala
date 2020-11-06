package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

/**
  * The Api mappings to be applied in order to shorten segment ids
  */
final case class ApiMappings(value: Map[String, Iri]) {
  lazy val (prefixMappings, aliases) = value.partition { case (_, iri) => iri.isPrefixMapping }
}

object ApiMappings {

  /**
    * An empty [[ApiMappings]]
    */
  val empty: ApiMappings = ApiMappings(Map.empty[String, Iri])

  final private case class Mapping(prefix: String, namespace: Iri)
  implicit private val mappingDecoder: Decoder[Mapping] = deriveDecoder[Mapping]
  implicit private val mappingEncoder: Encoder[Mapping] = deriveEncoder[Mapping]

  implicit val apiMappingsEncoder: Encoder[ApiMappings] =
    Encoder.encodeJson.contramap { case ApiMappings(mappings) =>
      mappings.map { case (prefix, namespace) => Mapping(prefix, namespace) }.asJson
    }

  implicit val apiMappingsDecoder: Decoder[ApiMappings] =
    Decoder.decodeList[Mapping].map(list => ApiMappings(list.map(m => m.prefix -> m.namespace).toMap))

}
