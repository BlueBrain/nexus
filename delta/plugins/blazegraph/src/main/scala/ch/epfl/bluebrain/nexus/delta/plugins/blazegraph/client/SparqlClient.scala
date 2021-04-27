package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import akka.http.scaladsl.model.{FormData, MediaRange, MediaType, Uri}
import cats.syntax.foldable._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.ScalaXmlSupport._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidUpdateRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.{SparqlJsonLdResponse, SparqlNTriplesResponse, SparqlRdfXmlResponse, SparqlResultsResponse, SparqlXmlResultsResponse}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.Json
import io.circe.syntax._
import monix.bio.IO
import org.apache.jena.query.ParameterizedSparqlString
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType._

import scala.util.Try
import scala.xml.{Elem, NodeSeq}

trait SparqlQueryClient {

  /**
    * Queries the passed indices with the passed ''q''.
    * The response type is controlled by the parameter ''responseType''
    *
    * @param indices      the namespaces to query
    * @param q            the query
    * @param responseType the response type
    */
  def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  ): IO[SparqlClientError, R]
}

/**
  * Sparql client implementing basic SPARQL query execution logic
  */
class SparqlClient(client: HttpClient, endpoint: SparqlQueryEndpoint)(implicit
    credentials: Option[HttpCredentials],
    as: ActorSystem
) extends SparqlQueryClient {

  import as.dispatcher

  def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R]
  ): IO[SparqlClientError, R] =
    responseType match {
      case SparqlResultsJson => sparqlResultsResponse(indices, q)
      case SparqlResultsXml  => sparqlXmlResultsResponse(indices, q)
      case SparqlJsonLd      => sparqlJsonLdResponse(indices, q)
      case SparqlNTriples    => sparqlNTriplesResponse(indices, q)
      case SparqlRdfXml      => sparqlRdfXmlResponse(indices, q)
    }

  /**
    * Executes the argument update queries against the underlying sparql endpoint.
    *
    * @param index   the sparql namespace
    * @param queries the write queries
    * @return successful Future[Unit] if update succeeded, failure otherwise
    */
  def bulk(index: String, queries: Seq[SparqlWriteQuery]): IO[SparqlClientError, Unit] = {
    val queryString = queries.map(_.value).mkString("\n")
    val pss         = new ParameterizedSparqlString
    pss.setCommandText(queryString)
    for {
      _          <- IO.fromTry(Try(pss.asUpdate())).mapError(th => InvalidUpdateRequest(index, queryString, th.getMessage))
      queryOpt    = uniqueGraph(queries).map(graph => Query("using-named-graph-uri" -> graph.toString))
      formData    = FormData("update" -> queryString)
      reqEndpoint = endpoint(index).withQuery(queryOpt.getOrElse(Query.Empty))
      req         = Post(reqEndpoint, formData).withHttpCredentials
      result     <- client.discardBytes(req, ()).mapError(WrappedHttpClientError)
    } yield result
  }

  /**
    * Executes the query that removes all triples from the graph identified by the argument URI
    * and stores the triples in the data argument in the same graph.
    *
    * @param index the sparql namespace
    * @param graph the target graph
    * @param data  the new graph as NTriples representation
    */
  def replace(index: String, graph: Uri, data: NTriples): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.replace(graph, data)))

  /**
    * Executes the query that patches the graph by selecting a collection of triples to remove or retain
    * and inserting the triples in the data argument.
    *
    * @param index    the sparql namespace
    * @param graph    the target graph as NTriples representation
    * @param data     the additional graph content
    * @param strategy the patch strategy
    */
  def patch(index: String, graph: Uri, data: NTriples, strategy: PatchStrategy): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.patch(graph, data, strategy)))

  /**
    * Executes the replace query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param index        the sparql namespace
    * @param graph        the graph to drop
    */
  def drop(index: String, graph: Uri): IO[SparqlClientError, Unit] =
    bulk(index, Seq(SparqlWriteQuery.drop(graph)))

  private def uniqueGraph(query: Seq[SparqlWriteQuery]): Option[Uri] =
    query.map(_.graph).distinct match {
      case head :: Nil => Some(head)
      case _           => None
    }

  private def sparqlResultsResponse(
      indices: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, SparqlResultsResponse] =
    indices.toList
      .foldLeftM(SparqlResults.empty) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlResultsJson.mediaTypes.value))
          .withHttpCredentials
        client.fromJsonTo[SparqlResults](req).mapError(WrappedHttpClientError).map(results ++ _)
      }
      .map(SparqlResultsResponse)

  private def sparqlXmlResultsResponse(
      indices: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, SparqlXmlResultsResponse] =
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlResultsXml.mediaTypes.value))
          .withHttpCredentials
        client.fromEntityTo[NodeSeq](req).mapError(WrappedHttpClientError).map { nodeSeq =>
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
      q: SparqlQuery
  ): IO[SparqlClientError, SparqlJsonLdResponse] =
    indices.toList
      .foldLeftM(Vector.empty[Json]) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlJsonLd.mediaTypes.value))
          .withHttpCredentials
        client
          .toJson(req)
          .mapError(WrappedHttpClientError)
          .map(results ++ _.arrayOrObject(Vector.empty[Json], identity, obj => Vector(obj.asJson)))
      }
      .map(vector => SparqlJsonLdResponse(Json.arr(vector: _*)))

  private def sparqlNTriplesResponse(
      indices: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, SparqlNTriplesResponse] =
    indices.toList
      .foldLeftM(NTriples.empty) { (results, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlNTriples.mediaTypes.value))
          .withHttpCredentials
        client.fromEntityTo[String](req).mapError(WrappedHttpClientError).map(s => results ++ NTriples(s, BNode.random))
      }
      .map(SparqlNTriplesResponse)

  private def sparqlRdfXmlResponse(
      indices: Iterable[String],
      q: SparqlQuery
  ): IO[SparqlClientError, SparqlRdfXmlResponse] =
    indices.toList
      .foldLeftM(None: Option[Elem]) { case (elem, index) =>
        val req = Post(endpoint(index), FormData("query" -> q.value))
          .withHeaders(accept(SparqlRdfXml.mediaTypes.value))
          .withHttpCredentials
        client.fromEntityTo[NodeSeq](req).mapError(WrappedHttpClientError).map { nodeSeq =>
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
