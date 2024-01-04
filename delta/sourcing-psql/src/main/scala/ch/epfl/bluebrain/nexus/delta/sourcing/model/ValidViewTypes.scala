package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.semiauto.deriveDefaultJsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ValidViewTypes.RestrictedTo
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

sealed trait ValidViewTypes {
  def asRestrictedTo: Option[RestrictedTo] = this match {
    case ValidViewTypes.All => None
    case r: RestrictedTo    => Some(r)
  }
}

object ValidViewTypes       {
  final case object All extends ValidViewTypes

  final case class RestrictedTo(types: Set[Iri]) extends ValidViewTypes

  def fromIris(types: Set[Iri]): ValidViewTypes = if (types.nonEmpty) RestrictedTo(types) else All
  def restrictedTo(iri: Iri): ValidViewTypes    = fromIris(Set(iri))

  implicit val enc: Encoder[ValidViewTypes] = {
    case All                 => Json.arr()
    case RestrictedTo(types) => types.asJson
  }

  implicit val dec: Decoder[ValidViewTypes] = Decoder[Set[Iri]].map(fromIris)

  implicit val jsonLdDec: JsonLdDecoder[ValidViewTypes] = {
    case class Tmp(types: Set[Iri]) // massaging for the auto-derivation, needs 'types' explicitly
    deriveDefaultJsonLdDecoder[Tmp].map(x => ValidViewTypes.fromIris(x.types))
  }
}
