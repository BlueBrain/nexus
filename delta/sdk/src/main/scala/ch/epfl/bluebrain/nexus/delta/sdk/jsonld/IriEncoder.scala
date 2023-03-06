package ch.epfl.bluebrain.nexus.delta.sdk.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import io.circe.Encoder
import io.circe.syntax.EncoderOps

/**
  * A type class that provides a way to produce a [[Iri]] value from a value of type [[A]].
  */
trait IriEncoder[-A] {

  /**
    * Encode a value of type [[A]] into an [[Iri]]
    */
  def apply(value: A)(implicit base: BaseUri): Iri
}

object IriEncoder {

  /**
    * Create an circe encoder to encode [[A]] as an [[Iri]]
    */
  def jsonEncoder[A](implicit base: BaseUri, iriEncoder: IriEncoder[A]): Encoder[A] =
    Encoder.encodeJson.contramap(iriEncoder(_).asJson)

  //It does not matter what the base is for ordering
  private val dummyValue: BaseUri = BaseUri("http://localhost", None)

  /**
    * Create an ordering based on the Iri representation of an [[A]]
    */
  def ordering[A](implicit iriEncoder: IriEncoder[A], ordering: Ordering[Iri]): Ordering[A] =
    Ordering.by[A, Iri](iriEncoder(_)(dummyValue))
}
