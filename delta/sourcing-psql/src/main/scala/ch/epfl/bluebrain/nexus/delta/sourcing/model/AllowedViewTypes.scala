package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.AllowedViewTypes.RestrictedTo
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

sealed trait AllowedViewTypes {
  def asRestrictedTo: Option[RestrictedTo] = this match {
    case AllowedViewTypes.All => None
    case r: RestrictedTo      => Some(r)
  }
}
object AllowedViewTypes       {

  def fromIris(types: Set[Iri]): AllowedViewTypes = if (types.nonEmpty) RestrictedTo(types) else All
  def fromIri(iri: Iri): AllowedViewTypes         = fromIris(Set(iri))

  final case object All                          extends AllowedViewTypes
  final case class RestrictedTo(types: Set[Iri]) extends AllowedViewTypes

  implicit val enc: Encoder[AllowedViewTypes] = {
    case All                 => Json.arr()
    case RestrictedTo(types) => types.asJson
  }
  implicit val dec: Decoder[AllowedViewTypes] = Decoder[Set[Iri]].map(fromIris)

  // TODO this derivation is probably wrong
  implicit val jsonLdDec: JsonLdDecoder[AllowedViewTypes] = deriveDefaultJsonLdDecoder
}
