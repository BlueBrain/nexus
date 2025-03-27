package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceUnmarshalling
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.{SparqlJsonLdResponse, SparqlNTriplesResponse, SparqlRdfXmlResponse, SparqlResultsResponse, SparqlXmlResultsResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import io.circe.Json
import io.circe.syntax._

import scala.concurrent.duration.Duration
import scala.reflect.ClassTag
import scala.xml.{Elem, NodeSeq}

trait SparqlQueryClient {

  /**
    * Queries the passed indices with the passed ''q''. The response type is controlled by the parameter
    * ''responseType''
    *
    * @param namespaces
    *   the namespaces to query
    * @param q
    *   the query
    * @param responseType
    *   the response type
    * @param additionalHeaders
    *   additional HTTP headers
    */
  def query[R <: SparqlQueryResponse](
      namespaces: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R],
      additionalHeaders: Seq[HttpHeader] = Seq.empty
  ): IO[R]
}

/**
  * Sparql client implementing basic SPARQL query execution logic
  */
trait SparqlClient extends SparqlQueryClient with XmlSupport {

  def serviceDescription: IO[ServiceDescription]

  /**
    * List all namespaces in the SPARQL instance
    */
  def listNamespaces: IO[Vector[String]]

  /**
    * Count all the triples on a namespace
    */
  def count(namespace: String): IO[Long]

  /**
    * Check whether the passed namespace ''namespace'' exists.
    */
  def existsNamespace(namespace: String): IO[Boolean]

  /**
    * Attempts to create a namespace (if it doesn't exist) recovering gracefully when the namespace already exists.
    *
    * @param namespace
    *   the namespace
    */
  def createNamespace(namespace: String): IO[Boolean]

  /**
    * Attempts to delete a namespace recovering gracefully when the namespace does not exists.
    */
  def deleteNamespace(namespace: String): IO[Boolean]

  def query[R <: SparqlQueryResponse](
      namespaces: Iterable[String],
      q: SparqlQuery,
      responseType: SparqlQueryResponseType.Aux[R],
      additionalHeaders: Seq[HttpHeader] = Seq.empty
  ): IO[R] =
    responseType match {
      case SparqlResultsJson => sparqlResultsResponse(namespaces, q, additionalHeaders)
      case SparqlResultsXml  => sparqlXmlResultsResponse(namespaces, q, additionalHeaders)
      case SparqlJsonLd      => sparqlJsonLdResponse(namespaces, q, additionalHeaders)
      case SparqlNTriples    => sparqlNTriplesResponse(namespaces, q, additionalHeaders)
      case SparqlRdfXml      => sparqlRdfXmlResponse(namespaces, q, additionalHeaders)
    }

  /**
    * Executes the argument update queries against the underlying sparql endpoint.
    *
    * @param namespace
    *   the sparql namespace
    * @param queries
    *   the write queries
    */
  def bulk(namespace: String, queries: Seq[SparqlWriteQuery]): IO[Unit]

  /**
    * Executes the query that removes all triples from the graph identified by the argument URI and stores the triples
    * in the data argument in the same graph.
    *
    * @param namespace
    *   the sparql namespace
    * @param graph
    *   the target graph
    * @param data
    *   the new graph as NTriples representation
    */
  def replace(namespace: String, graph: Uri, data: NTriples): IO[Unit] =
    bulk(namespace, Seq(SparqlWriteQuery.replace(graph, data)))

  /**
    * Executes the query that patches the graph by selecting a collection of triples to remove or retain and inserting
    * the triples in the data argument.
    *
    * @param namespace
    *   the sparql namespace
    * @param graph
    *   the target graph as NTriples representation
    * @param data
    *   the additional graph content
    * @param strategy
    *   the patch strategy
    */
  def patch(namespace: String, graph: Uri, data: NTriples, strategy: PatchStrategy): IO[Unit] =
    bulk(namespace, Seq(SparqlWriteQuery.patch(graph, data, strategy)))

  /**
    * Executes the replace query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param namespace
    *   the sparql namespace
    * @param graph
    *   the graph to drop
    */
  def drop(namespace: String, graph: Uri): IO[Unit] =
    bulk(namespace, Seq(SparqlWriteQuery.drop(graph)))

  protected def queryRequest[A: FromEntityUnmarshaller: ClassTag](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[HttpHeader]
  ): IO[A]

  private def sparqlResultsResponse(
      namespace: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlResultsResponse] =
    namespace.toList
      .foldLeftM(SparqlResults.empty) { (results, namespace) =>
        queryRequest[SparqlResults](namespace, q, SparqlResultsJson.mediaTypes, additionalHeaders)
          .map(results ++ _)
      }
      .map(SparqlResultsResponse)

  private def sparqlXmlResultsResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlXmlResultsResponse] =
    namespaces.toList
      .foldLeftM(None: Option[Elem]) { case (elem, namespace) =>
        queryRequest[NodeSeq](namespace, q, SparqlResultsXml.mediaTypes, additionalHeaders)
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
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlJsonLdResponse] = {
    import CirceUnmarshalling._
    namespaces.toList
      .foldLeftM(Vector.empty[Json]) { (results, namespace) =>
        queryRequest[Json](namespace, q, SparqlJsonLd.mediaTypes, additionalHeaders)
          .map(results ++ _.arrayOrObject(Vector.empty[Json], identity, obj => Vector(obj.asJson)))
      }
      .map(vector => SparqlJsonLdResponse(Json.arr(vector: _*)))
  }

  private def sparqlNTriplesResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlNTriplesResponse] =
    namespaces.toList
      .foldLeftM(NTriples.empty) { (results, namspace) =>
        queryRequest[String](namspace, q, SparqlNTriples.mediaTypes, additionalHeaders)
          .map(s => results ++ NTriples(s, BNode.random))
      }
      .map(SparqlNTriplesResponse)

  private def sparqlRdfXmlResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlRdfXmlResponse] =
    namespaces.toList
      .foldLeftM(None: Option[Elem]) { case (elem, namespace) =>
        queryRequest[NodeSeq](namespace, q, SparqlRdfXml.mediaTypes, additionalHeaders)
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

  protected def accept(mediaType: Seq[MediaType]): Accept =
    Accept(mediaType.map(MediaRange.One(_, 1f)))
}

object SparqlClient {

  def query(target: SparqlTarget, endpoint: Uri, queryTimeout: Duration)(implicit
      credentials: Option[HttpCredentials],
      as: ActorSystem
  ): SparqlClient = {
    val httpConfig = HttpClientConfig.noRetry(compression = false)
    indexing(target, endpoint, httpConfig, queryTimeout)
  }

  def indexing(target: SparqlTarget, endpoint: Uri, httpConfig: HttpClientConfig, queryTimeout: Duration)(implicit
      credentials: Option[HttpCredentials],
      as: ActorSystem
  ): SparqlClient = {

    target match {
      case SparqlTarget.Blazegraph =>
        // Blazegraph can't handle compressed requests/responses
        val client = HttpClient()(httpConfig, as)
        new BlazegraphClient(client, endpoint, queryTimeout)
      case SparqlTarget.Rdf4j      =>
        // RDF4J does so we enable it
        val client = HttpClient()(httpConfig.copy(compression = true), as)
        RDF4JClient.lmdb(client, endpoint)
    }
  }
}
