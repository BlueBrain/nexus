package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import scala.annotation.nowarn

/**
  * The Api mappings to be applied in order to shorten segment ids
  */
final case class ApiMappings(value: Map[String, Iri]) {
  lazy val (prefixMappings, aliases) = value.partition { case (_, iri) => iri.isPrefixMapping }

  /**
    * Append the passed ''that'' [[ApiMappings]] to the current [[ApiMappings]].
    * If some prefixes are colliding, the ones in the ''that'' [[ApiMappings]] will override the current ones.
    */
  def +(that: ApiMappings): ApiMappings = ApiMappings(value ++ that.value)
}

object ApiMappings {

  /**
    * The default API mappings
    */
  val default: ApiMappings =
    ApiMappings(
      Map(
        "_"        -> schemas.resources,
        "schema"   -> schemas.shacl,
        "resolver" -> schemas.resolvers,
        "resource" -> schemas.resources
      )
    )

  /**
    * An empty [[ApiMappings]]
    */
  val empty: ApiMappings = ApiMappings(Map.empty[String, Iri])

  final private case class Mapping(prefix: String, namespace: Iri)

  @nowarn("cat=unused")
  implicit final private val configuration: Configuration = Configuration.default.withStrictDecoding

  implicit private val mappingDecoder: Decoder[Mapping] = deriveConfiguredDecoder[Mapping]
  implicit private val mappingEncoder: Encoder[Mapping] = deriveConfiguredEncoder[Mapping]

  implicit val apiMappingsEncoder: Encoder[ApiMappings] =
    Encoder.encodeJson.contramap { case ApiMappings(mappings) =>
      mappings.map { case (prefix, namespace) => Mapping(prefix, namespace) }.asJson
    }

  implicit val apiMappingsDecoder: Decoder[ApiMappings] =
    Decoder.decodeList[Mapping].map(list => ApiMappings(list.map(m => m.prefix -> m.namespace).toMap))

}
