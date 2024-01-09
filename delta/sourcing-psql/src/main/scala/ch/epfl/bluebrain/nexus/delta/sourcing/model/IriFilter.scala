package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.IriFilter.Include
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

sealed trait IriFilter {
  def asRestrictedTo: Option[Include] = this match {
    case IriFilter.None => None
    case r: Include     => Some(r)
  }
}

object IriFilter {
  final case object None                             extends IriFilter
  sealed abstract case class Include(iris: Set[Iri]) extends IriFilter

  def fromSet(iris: Set[Iri]): IriFilter = if (iris.nonEmpty) new Include(iris) {}
  else None
  def restrictedTo(iri: Iri): IriFilter  = fromSet(Set(iri))

  implicit val enc: Encoder[IriFilter]             = {
    case None          => Json.arr()
    case Include(iris) => iris.asJson
  }
  implicit val dec: Decoder[IriFilter]             = Decoder[Set[Iri]].map(fromSet)
  implicit val jsonLdDec: JsonLdDecoder[IriFilter] = JsonLdDecoder[Set[Iri]].map(fromSet)
}
