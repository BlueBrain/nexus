package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import cats.syntax.foldable._
import akka.http.scaladsl.client.RequestBuilding.Post
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Accept, HttpCredentials}
import akka.http.scaladsl.model.{FormData, MediaRange, Uri}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidUpdateRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NTriples
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.IO
import org.apache.jena.query.ParameterizedSparqlString

import scala.util.Try

/**
  * Sparql client implementing basic SPARQL query execution logic
  */
class SparqlClient(client: HttpClient, endpoint: SparqlQueryEndpoint)(implicit
    credentials: Option[HttpCredentials],
    as: ActorSystem
) {

  import as.dispatcher
  private val accept = Accept(MediaRange.One(RdfMediaTypes.`application/sparql-results+json`, 1f))

  /**
    * @param indices the sparql namespaces
    * @param q       the query to execute against the sparql endpoint
    * @return the raw result of the provided query executed against the sparql endpoint
    */
  def query(indices: Set[String], q: String): IO[SparqlClientError, SparqlResults] =
    indices.toList.foldLeftM(SparqlResults.empty) { (results, index) =>
      query(index, q).map(results ++ _)
    }

  private def query(index: String, q: String): IO[SparqlClientError, SparqlResults] = {
    val req = Post(endpoint(index), FormData("query" -> q)).withHeaders(accept).withHttpCredentials
    client.fromJsonTo[SparqlResults](req).mapError(WrappedHttpClientError)
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
}
