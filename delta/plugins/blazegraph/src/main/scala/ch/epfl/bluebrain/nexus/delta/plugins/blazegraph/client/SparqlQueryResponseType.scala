package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.http.scaladsl.model.{HttpCharsets, MediaType}
import cats.data.NonEmptyList
import cats.implicits._
import cats.Eq
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes._

/**
  * Enumeration of supported sparql query response types
  */
sealed trait SparqlQueryResponseType extends Product with Serializable {

  /**
    * @return
    *   the media types supported by this response type
    */
  def mediaTypes: NonEmptyList[MediaType.WithFixedCharset]

  type R <: SparqlQueryResponse
}

object SparqlQueryResponseType {

  type Aux[R0] = SparqlQueryResponseType { type R = R0 }
  type Generic = SparqlQueryResponseType { type R = SparqlQueryResponse }

  private val `text/plain(UTF-8)` = MediaType.textWithFixedCharset("plain", HttpCharsets.`UTF-8`, "nt", "txt")

  implicit val mediaTypqEq: Eq[MediaType.WithFixedCharset] = Eq.fromUniversalEquals

  /**
    * Constructor helper that creates a [[SparqlQueryResponseType]] from the passed ''mediaType''
    */
  def fromMediaType(mediaType: MediaType): Option[SparqlQueryResponseType] = {
    mediaType match {
      case mediaType: MediaType.WithFixedCharset =>
        if (SparqlResultsJson.mediaTypes.contains_(mediaType)) Some(SparqlResultsJson)
        else if (SparqlResultsXml.mediaTypes.contains_(mediaType)) Some(SparqlResultsXml)
        else if (SparqlJsonLd.mediaTypes.contains_(mediaType)) Some(SparqlJsonLd)
        else if (SparqlNTriples.mediaTypes.contains_(mediaType)) Some(SparqlNTriples)
        else if (SparqlRdfXml.mediaTypes.contains_(mediaType)) Some(SparqlRdfXml)
        else None
      case _                                     => None
    }
  }

  final case object SparqlResultsJson extends SparqlQueryResponseType {
    override type R = SparqlResultsResponse
    override val mediaTypes: NonEmptyList[MediaType.WithFixedCharset] =
      NonEmptyList.of(`application/sparql-results+json`)
  }

  final case object SparqlResultsXml extends SparqlQueryResponseType {
    override type R = SparqlXmlResultsResponse
    override val mediaTypes: NonEmptyList[MediaType.WithFixedCharset] =
      NonEmptyList.of(`application/sparql-results+xml`)
  }

  final case object SparqlJsonLd extends SparqlQueryResponseType {
    override type R = SparqlJsonLdResponse
    override val mediaTypes: NonEmptyList[MediaType.WithFixedCharset] =
      NonEmptyList.of(`application/ld+json`)
  }

  final case object SparqlNTriples extends SparqlQueryResponseType {
    override type R = SparqlNTriplesResponse
    override val mediaTypes: NonEmptyList[MediaType.WithFixedCharset] =
      NonEmptyList.of(`text/plain(UTF-8)`, `application/n-triples`)
  }

  final case object SparqlRdfXml extends SparqlQueryResponseType {
    override type R = SparqlRdfXmlResponse
    override val mediaTypes: NonEmptyList[MediaType.WithFixedCharset] =
      NonEmptyList.of(`application/rdf+xml`)
  }
}
