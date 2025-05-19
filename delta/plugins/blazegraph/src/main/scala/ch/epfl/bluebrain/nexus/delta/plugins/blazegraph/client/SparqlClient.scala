package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.client.middleware.BasicAuth
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponse.{SparqlJsonLdResponse, SparqlNTriplesResponse, SparqlRdfXmlResponse, SparqlResultsResponse, SparqlXmlResultsResponse}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.*
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import io.circe.Json
import io.circe.syntax.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Accept
import org.http4s.{BasicCredentials, EntityDecoder, Header, MediaType, QValue, Uri}

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
      additionalHeaders: Seq[Header.ToRaw] = Seq.empty
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
      additionalHeaders: Seq[Header.ToRaw] = Seq.empty
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

  protected def queryRequest[A](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[Header.ToRaw]
  )(implicit entityDecoder: EntityDecoder[IO, A], classTag: ClassTag[A]): IO[A]

  private def sparqlResultsResponse(
      namespace: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[SparqlResultsResponse] = {
    import ch.epfl.bluebrain.nexus.delta.kernel.http.circe.CirceEntityDecoder.*
    namespace.toList
      .foldLeftM(SparqlResults.empty) { (results, namespace) =>
        queryRequest[SparqlResults](namespace, q, SparqlResultsJson.mediaTypes, additionalHeaders)
          .map(results ++ _)
      }
      .map(SparqlResultsResponse)
  }

  private def sparqlXmlResultsResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[SparqlXmlResultsResponse] =
    namespaces.toList
      .foldLeftM(None: Option[Elem]) { case (acc, namespace) =>
        queryRequest[Elem](namespace, q, SparqlResultsXml.mediaTypes, additionalHeaders)
          .map { result =>
            acc match {
              case Some(root) =>
                val results = (root \ "results").collect { case results: Elem =>
                  results.copy(child = results.child ++ result \\ "result")
                }
                Some(root.copy(child = root.child.filterNot(_.label == "results") ++ results))
              case None       => result.headOption.collect { case root: Elem => root }
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
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[SparqlJsonLdResponse] = {
    import ch.epfl.bluebrain.nexus.delta.kernel.http.circe.CirceEntityDecoder.*
    namespaces.toList
      .foldLeftM(Vector.empty[Json]) { (results, namespace) =>
        queryRequest[Json](namespace, q, SparqlJsonLd.mediaTypes, additionalHeaders)
          .map(results ++ _.arrayOrObject(Vector.empty[Json], identity, obj => Vector(obj.asJson)))
      }
      .map(vector => SparqlJsonLdResponse(Json.arr(vector*)))
  }

  private def sparqlNTriplesResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[SparqlNTriplesResponse] =
    namespaces.toList
      .foldLeftM(NTriples.empty) { (results, namespace) =>
        queryRequest[String](namespace, q, SparqlNTriples.mediaTypes, additionalHeaders)
          .map(s => results ++ NTriples(s, BNode.random))
      }
      .map(SparqlNTriplesResponse)

  private def sparqlRdfXmlResponse(
      namespaces: Iterable[String],
      q: SparqlQuery,
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[SparqlRdfXmlResponse] =
    namespaces.toList
      .foldLeftM(None: Option[Elem]) { case (acc, namespace) =>
        queryRequest[Elem](namespace, q, SparqlRdfXml.mediaTypes, additionalHeaders)
          .map { result =>
            acc match {
              case Some(root) => Some(root.copy(child = root.child ++ result \ "_"))
              case None       => result.headOption.collect { case root: Elem => root }
            }
          }
      }
      .map {
        case Some(elem) => SparqlRdfXmlResponse(elem)
        case None       => SparqlRdfXmlResponse(NodeSeq.Empty)
      }

  protected def accept(mediaTypes: NonEmptyList[MediaType]): Accept =
    Accept(mediaTypes.map(_.withQValue(QValue.One)))
}

object SparqlClient {

  private val logger = Logger[this.type]

  def apply(
      target: SparqlTarget,
      endpoint: Uri,
      queryTimeout: Duration,
      credentials: Option[BasicCredentials]
  ): Resource[IO, SparqlClient] =
    EmberClientBuilder
      .default[IO]
      .withLogger(logger)
      .build
      .map { client =>
        val authClient = BasicAuth(credentials)(client)
        target match {
          case SparqlTarget.Blazegraph =>
            // Blazegraph can't handle compressed requests
            new BlazegraphClient(authClient, endpoint, queryTimeout)
          case SparqlTarget.Rdf4j      =>
            RDF4JClient.lmdb(authClient, endpoint)
        }
      }
}
