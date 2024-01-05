package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.decoder.JsonLdDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ViewRestriction.RestrictedTo
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}

sealed trait ViewRestriction {
  def asRestrictedTo: Option[RestrictedTo] = this match {
    case ViewRestriction.None => None
    case r: RestrictedTo      => Some(r)
  }
}

object ViewRestriction {
  final case object None                                  extends ViewRestriction
  sealed abstract case class RestrictedTo(iris: Set[Iri]) extends ViewRestriction

  def fromSet(iris: Set[Iri]): ViewRestriction = if (iris.nonEmpty) new RestrictedTo(iris) {}
  else None
  def restrictedTo(iri: Iri): ViewRestriction  = fromSet(Set(iri))

  implicit val enc: Encoder[ViewRestriction]             = {
    case None               => Json.arr()
    case RestrictedTo(iris) => iris.asJson
  }
  implicit val dec: Decoder[ViewRestriction]             = Decoder[Set[Iri]].map(fromSet)
  implicit val jsonLdDec: JsonLdDecoder[ViewRestriction] = JsonLdDecoder[Set[Iri]].map(fromSet)
}
