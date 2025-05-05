package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.MediaType
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes
import RdfMediaTypes.{`application/ld+json`, `application/sparql-results+json`}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfMarshalling.customContentTypeJsonMarshaller
import io.circe.Json
import io.circe.syntax.*

import scala.xml.NodeSeq

/**
  * Enumeration of supported sparql query responses
  */
sealed trait SparqlQueryResponse extends Product with Serializable

object SparqlQueryResponse {

  private val jsonMediaTypes = List(`application/sparql-results+json`, `application/ld+json`)

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

  private val xmlMediaTypes: Seq[MediaType.NonBinary] =
    List(RdfMediaTypes.`application/rdf+xml`, RdfMediaTypes.`application/sparql-results+xml`)

  implicit val nodeSeqMarshaller: ToEntityMarshaller[NodeSeq] =
    Marshaller.oneOf(xmlMediaTypes.map(ScalaXmlSupport.nodeSeqMarshaller)*)

  /**
    * SparqlQueryResponse -> HttpEntity
    */
  implicit def sparqlQueryRespMarshaller(implicit ordering: JsonKeyOrdering): ToEntityMarshaller[SparqlQueryResponse] =
    Marshaller { implicit ec =>
      {
        case SparqlResultsResponse(value)    => jsonMarshaller.apply(value.asJson)
        case SparqlXmlResultsResponse(value) => nodeSeqMarshaller.apply(value)
        case SparqlJsonLdResponse(value)     => jsonMarshaller.apply(value)
        case SparqlNTriplesResponse(value)   => RdfMarshalling.nTriplesMarshaller.apply(value)
        case SparqlRdfXmlResponse(value)     => nodeSeqMarshaller.apply(value)
      }
    }

  private def jsonMarshaller(implicit ordering: JsonKeyOrdering) =
    Marshaller.oneOf(jsonMediaTypes.map(mt => customContentTypeJsonMarshaller(mt.toContentType))*)
}
