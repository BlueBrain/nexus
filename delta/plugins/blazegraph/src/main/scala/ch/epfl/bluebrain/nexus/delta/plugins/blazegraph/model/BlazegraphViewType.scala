package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import io.circe.{Decoder, Encoder, Json}

/**
  * Enumeration of Blazegraph view types.
  */
sealed trait BlazegraphViewType extends Product with Serializable {

  /**
    * @return
    *   the type id
    */
  def tpe: Iri

  /**
    * @return
    *   RDF types of the view
    */
  def types: Set[Iri] = Set(tpe, nxv + "View")

}

object BlazegraphViewType {

  /**
    * Blazegraph view that indexes resources as triples.
    */
  final case object IndexingBlazegraphView extends BlazegraphViewType {
    override val toString: String = "SparqlView"

    override def tpe: Iri = nxv + toString
  }

  /**
    * Blazegraph view that delegates queries to a collections of existing Blazegraph views based on access.
    */
  final case object AggregateBlazegraphView extends BlazegraphViewType {
    override val toString: String = "AggregateSparqlView"

    override def tpe: Iri = nxv + toString
  }

  implicit final val blazegraphViewTypeEncoder: Encoder[BlazegraphViewType] = Encoder.instance {
    case IndexingBlazegraphView  => Json.fromString("BlazegraphView")
    case AggregateBlazegraphView => Json.fromString("AggregateBlazegraphView")
  }

  implicit final val blazegraphViewTypeDecoder: Decoder[BlazegraphViewType] = Decoder.decodeString.emap {
    case "BlazegraphView"          => Right(IndexingBlazegraphView)
    case "AggregateBlazegraphView" => Right(AggregateBlazegraphView)
  }
}
