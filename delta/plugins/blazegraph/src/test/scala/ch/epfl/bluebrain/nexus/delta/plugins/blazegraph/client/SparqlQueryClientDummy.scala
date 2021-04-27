package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryClientDummy.bNode
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{Aux, SparqlJsonLd, SparqlNTriples, SparqlRdfXml, SparqlResultsJson, SparqlResultsXml}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import io.circe.Json
import monix.bio.IO

import scala.xml.NodeSeq

class SparqlQueryClientDummy(
    sparqlResults: Iterable[String] => SparqlResults = _ => SparqlResults.empty,
    sparqlResultsXml: Iterable[String] => NodeSeq = _ => NodeSeq.Empty,
    sparqlJsonLd: Iterable[String] => Json = _ => Json.obj(),
    sparqlNTriples: Iterable[String] => NTriples = _ => NTriples("", bNode),
    sparqlRdfXml: Iterable[String] => NodeSeq = _ => NodeSeq.Empty
) extends SparqlQueryClient {
  override def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: Aux[R]
  ): IO[SparqlClientError, R] =
    responseType match {
      case SparqlResultsJson =>
        IO.pure(SparqlResultsResponse(sparqlResults(indices)))
      case SparqlResultsXml  => IO.pure(SparqlXmlResultsResponse(sparqlResultsXml(indices)))
      case SparqlJsonLd      => IO.pure(SparqlJsonLdResponse(sparqlJsonLd(indices)))
      case SparqlNTriples    => IO.pure(SparqlNTriplesResponse(sparqlNTriples(indices)))
      case SparqlRdfXml      => IO.pure(SparqlRdfXmlResponse(sparqlRdfXml(indices)))
    }

}

object SparqlQueryClientDummy {
  val bNode = BNode.random
}
