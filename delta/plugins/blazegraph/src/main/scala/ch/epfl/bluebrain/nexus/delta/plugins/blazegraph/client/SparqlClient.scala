package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidUpdateRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.{SparqlJsonLdResponse, SparqlNTriplesResponse, SparqlRdfXmlResponse, SparqlResultsResponse, SparqlXmlResultsResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.query.ParameterizedSparqlString

import scala.xml.{Elem, NodeSeq}

trait SparqlQueryClient {

  /**
    * Queries the passed indices with the passed ''q''. The response type is controlled by the parameter
    * ''responseType''
    *
    * @param indices
    *   the namespaces to query
    * @param q
    *   the query
    * @param responseType
    *   the response type
    * @param additionalHeaders
    *   additional HTTP headers
    */
  def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R],
      additionalHeaders: Seq[HttpHeader] = Seq.empty
  ): IO[R]
}

/**
  * Sparql client implementing basic SPARQL query execution logic
  */
class SparqlClient(client: HttpClient, endpoint: SparqlQueryEndpoint)(implicit
    credentials: Option[HttpCredentials],
    as: ActorSystem
) extends SparqlQueryClient
    with XmlSupport {

  import as.dispatcher

  def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R],
      additionalHeaders: Seq[HttpHeader] = Seq.empty
  ): IO[R] =
    responseType match {
      case SparqlResultsJson => sparqlResultsResponse(indices, q, additionalHeaders)
      case SparqlResultsXml  => sparqlXmlResultsResponse(indices, q, additionalHeaders)
      case SparqlJsonLd      => sparqlJsonLdResponse(indices, q, additionalHeaders)
      case SparqlNTriples    => sparqlNTriplesResponse(indices, q, additionalHeaders)
      case SparqlRdfXml      => sparqlRdfXmlResponse(indices, q, additionalHeaders)
    }

  /**
    * Executes the argument update queries against the underlying sparql endpoint.
    *
    * @param index
    *   the sparql namespace
    * @param queries
    *   the write queries
    * @return
    *   successful Future[Unit] if update succeeded, failure otherwise
    */
  def bulk(index: String, queries: Seq[SparqlWriteQuery]): IO[Unit] = {
    val queryString = queries.map(_.value).mkString("\n")
    val pss         = new ParameterizedSparqlString
    pss.setCommandText(queryString)
    for {
      _          <- IO(pss.asUpdate()).adaptError(e => InvalidUpdateRequest(index, queryString, e.getMessage))
      queryOpt    = uniqueGraph(queries).map(graph => Query("using-named-graph-uri" -> graph.toString))
      formData    = FormData("update" -> queryString)
      reqEndpoint = endpoint(index).withQuery(queryOpt.getOrElse(Query.Empty))
      req         = Post(reqEndpoint, formData).withHttpCredentials
      result     <-
        client.discardBytes(req, ()).adaptError { case e: HttpClientError => WrappedHttpClientError(e) }
    } yield result
  }

  /**
    * Executes the query that removes all triples from the graph identified by the argument URI and stores the triples
    * in the data argument in the same graph.
    *
    * @param index
    *   the sparql namespace
    * @param graph
    *   the target graph
    * @param data
    *   the new graph as NTriples representation
    */
  def replace(index: String, graph: Uri, data: NTriples): IO[Unit] =
    bulk(index, Seq(SparqlWriteQuery.replace(graph, data)))

  /**
    * Executes the query that patches the graph by selecting a collection of triples to remove or retain and inserting
    * the triples in the data argument.
    *
    * @param index
    *   the sparql namespace
    * @param graph
    *   the target graph as NTriples representation
    * @param data
    *   the additional graph content
    * @param strategy
    *   the patch strategy
    */
  def patch(index: String, graph: Uri, data: NTriples, strategy: PatchStrategy): IO[Unit] =
    bulk(index, Seq(SparqlWriteQuery.patch(graph, data, strategy)))

  /**
    * Executes the replace query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param index
    *   the sparql namespace
    * @param graph
    *   the graph to drop
    */
  def drop(index: String, graph: Uri): IO[Unit] =
    bulk(index, Seq(SparqlWriteQuery.drop(graph)))

  private def uniqueGraph(query: Seq[SparqlWriteQuery]): Option[Uri] =
    query.map(_.graph).distinct match {
      case head :: Nil => Some(head)
      case _           => None
    }

  private def sparqlResultsResponse(
      indices: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlResultsResponse] =
    indices.toList
      .foldLeftM(SparqlResults.empty) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlResultsJson.mediaTypes.toList), additionalHeaders: _*)
          .withHttpCredentials
        client
          .fromJsonTo[SparqlResults](req)
          .adaptError { case e: HttpClientError =>
            WrappedHttpClientError(e)
          }
          .map(results ++ _)
      }
      .map(SparqlResultsResponse)

  private def sparqlXmlResultsResponse(
      indices: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlXmlResultsResponse] =
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlResultsXml.mediaTypes.toList), additionalHeaders: _*)
          .withHttpCredentials
        client
          .fromEntityTo[NodeSeq](req)
          .adaptError { case e: HttpClientError =>
            WrappedHttpClientError(e)
          }
          .map { nodeSeq =>
            elem match {
              case Some(root) =>
                val results = (root \ "results").collect { case results: Elem =>
                  results.copy(child = results.child ++ nodeSeq \\ "result")
                }
                Some(root.copy(child = root.child.filterNot(_.label == "results") ++ results))
              case None       => nodeSeq.headOption.collect { case root: Elem => root }
            }
          }
      }
      .map {
        case Some(elem) => SparqlXmlResultsResponse(elem)
        case None       => SparqlXmlResultsResponse(NodeSeq.Empty)
      }

  private def sparqlJsonLdResponse(
      indices: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlJsonLdResponse] =
    indices.toList
      .foldLeftM(Vector.empty[Json]) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlJsonLd.mediaTypes.toList), additionalHeaders: _*)
          .withHttpCredentials
        client
          .toJson(req)
          .adaptError { case e: HttpClientError =>
            WrappedHttpClientError(e)
          }
          .map(results ++ _.arrayOrObject(Vector.empty[Json], identity, obj => Vector(obj.asJson)))
      }
      .map(vector => SparqlJsonLdResponse(Json.arr(vector: _*)))

  private def sparqlNTriplesResponse(
      indices: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlNTriplesResponse] =
    indices.toList
      .foldLeftM(NTriples.empty) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlNTriples.mediaTypes.toList), additionalHeaders: _*)
          .withHttpCredentials
        client
          .fromEntityTo[String](req)
          .adaptError { case e: HttpClientError =>
            WrappedHttpClientError(e)
          }
          .map(s => results ++ NTriples(s, BNode.random))
      }
      .map(SparqlNTriplesResponse)

  private def sparqlRdfXmlResponse(
      indices: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlRdfXmlResponse] =
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlRdfXml.mediaTypes.toList), additionalHeaders: _*)
          .withHttpCredentials
        client
          .fromEntityTo[NodeSeq](req)
          .adaptError { case e: HttpClientError =>
            WrappedHttpClientError(e)
          }
          .map { nodeSeq =>
            elem match {
              case Some(root) => Some(root.copy(child = root.child ++ nodeSeq \ "_"))
              case None       => nodeSeq.headOption.collect { case root: Elem => root }
            }
          }
      }
      .map {
        case Some(elem) => SparqlRdfXmlResponse(elem)
        case None       => SparqlRdfXmlResponse(NodeSeq.Empty)
      }

  private def accept(mediaType: Seq[MediaType]): Accept =
    Accept(mediaType.map(MediaRange.One(_, 1f)))
}
