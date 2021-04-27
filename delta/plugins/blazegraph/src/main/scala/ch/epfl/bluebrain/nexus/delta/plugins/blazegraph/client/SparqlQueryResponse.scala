package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{SparqlJsonLd, SparqlResultsJson}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.customContentTypeJsonMarshaller
import io.circe.Json
import io.circe.syntax._

import scala.xml.NodeSeq

/**
  * Enumeration of supported sparql query responses
  */
sealed trait SparqlQueryResponse extends Product with Serializable

object SparqlQueryResponse {

  private val jsonMediaTypes =
    SparqlResultsJson.mediaTypes.value ++ SparqlJsonLd.mediaTypes.value

  /**
    * Sparql response returned when using application/sparql-results+json Accept header
    */
  final case class SparqlResultsResponse(value: SparqlResults) extends SparqlQueryResponse

  /**
    * Sparql response returned when using application/sparql-results+xml Accept header
    */
  final case class SparqlXmlResultsResponse(value: NodeSeq) extends SparqlQueryResponse

  /**
    * Sparql response returned when using application/ld+json Accept header (if the query itself supports it)
    */
  final case class SparqlJsonLdResponse(value: Json) extends SparqlQueryResponse

  /**
    * Sparql response returned when using application/n-triples Accept header (if the query itself supports it)
    */
  final case class SparqlNTriplesResponse(value: NTriples) extends SparqlQueryResponse

  /**
    * Sparql response returned when using application/rdf+xml Accept header (if the query itself supports it)
    */
  final case class SparqlRdfXmlResponse(value: NodeSeq) extends SparqlQueryResponse

  /**
    * SparqlQueryResponse -> HttpEntity
    */
  implicit def sparqlQueryRespMarshaller(implicit ordering: JsonKeyOrdering): ToEntityMarshaller[SparqlQueryResponse] =
    Marshaller { implicit ec =>
      {
        case SparqlResultsResponse(value)    => jsonMarshaller.apply(value.asJson)
        case SparqlXmlResultsResponse(value) => ScalaXmlSupport.defaultNodeSeqMarshaller.apply(value)
        case SparqlJsonLdResponse(value)     => jsonMarshaller.apply(value)
        case SparqlNTriplesResponse(value)   => RdfMarshalling.nTriplesMarshaller.apply(value)
        case SparqlRdfXmlResponse(value)     => ScalaXmlSupport.defaultNodeSeqMarshaller.apply(value)
      }
    }

  private def jsonMarshaller(implicit ordering: JsonKeyOrdering) =
    Marshaller.oneOf(jsonMediaTypes.map(mt => customContentTypeJsonMarshaller(mt.toContentType)): _*)
}
