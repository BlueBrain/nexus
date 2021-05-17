package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import io.circe.Encoder

/**
  * Enumeration of composite view source types
  */
sealed trait ProjectionType {

  /**
    * @return the type id
    */
  def tpe: Iri

  /**
    * @return the full set of types
    */
  def types: Set[Iri] = Set(tpe, nxv + "CompositeViewProjection")
}

object ProjectionType {

  /**
    * ElasticSearch projection.
    */
  final case object ElasticSearchProjectionType extends ProjectionType {

    override val toString: String = "ElasticSearchProjection"

    override def tpe: Iri = nxv + toString
  }

  /**
    * SPARQL projection.
    */
  final case object SparqlProjectionType extends ProjectionType {

    override val toString: String = "SparqlProjection"

    override def tpe: Iri = nxv + toString
  }

  implicit val projectionTypeEncoder: Encoder[ProjectionType] = Encoder.encodeString.contramap(_.tpe.toString)
}
