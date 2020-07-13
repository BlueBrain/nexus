package ch.epfl.bluebrain.nexus.commons.sparql.client

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlFailure.{SparqlServerError, SparqlUnexpectedError}
import ch.epfl.bluebrain.nexus.rdf.Graph
import akka.http.scaladsl.model.StatusCodes.GatewayTimeout

/**
  * Base sparql client implementing basic SPARQL query execution logic
  */
abstract class SparqlClient[F[_]]()(implicit rsJson: HttpClient[F, SparqlResults]) {

  protected val defaultWorthRetry: Throwable => Boolean = {
    case SparqlServerError(code, _) if code != GatewayTimeout     => true
    case SparqlUnexpectedError(code, _) if code != GatewayTimeout => true
    case _                                                        => false
  }

  /**
    * @param q            the query to execute against the sparql endpoint
    * @param isWorthRetry a function to decide if it is needed to retry
    * @return the raw result of the provided query executed against the sparql endpoint
    */
  def queryRaw(q: String, isWorthRetry: (Throwable => Boolean) = defaultWorthRetry): F[SparqlResults] =
    query(q, isWorthRetry)(rsJson)

  /**
    *
    * @param query        the query to execute against the sparql endpoint
    * @param isWorthRetry a function to decide if it is needed to retry
    * @tparam A the generic type of the unmarshalled response
    * @return the unmarshalled result ''A'' of the provided query executed against the sparql endpoint
    */
  def query[A](query: String, isWorthRetry: (Throwable => Boolean))(implicit rs: HttpClient[F, A]): F[A]

  /**
    * Executes the argument update queries against the underlying sparql endpoint.
    *
    * @param queries      the write queries
    * @param isWorthRetry a function to decide if it is needed to retry
    * @return successful Future[Unit] if update succeeded, failure otherwise
    */
  def bulk(queries: Seq[SparqlWriteQuery], isWorthRetry: (Throwable => Boolean)): F[Unit]

  /**
    * Executes the query that removes all triples from the graph identified by the argument URI and stores the triples in the data argument in
    * the same graph.
    *
    * @param graph        the target graph
    * @param data         the new graph content
    * @param isWorthRetry a function to decide if it is needed to retry
    */
  def replace(graph: Uri, data: Graph, isWorthRetry: (Throwable => Boolean) = defaultWorthRetry): F[Unit] =
    bulk(Seq(SparqlWriteQuery.replace(graph, data)), isWorthRetry)

  /**
    * Executes the query that patches the graph by selecting a collection of triples to remove or retain and inserting the triples in the data
    * argument.
    *
    * @see [[ch.epfl.bluebrain.nexus.commons.sparql.client.PatchStrategy]]
    * @param graph        the target graph
    * @param data         the additional graph content
    * @param strategy     the patch strategy
    * @param isWorthRetry a function to decide if it is needed to retry
    */
  def patch(
      graph: Uri,
      data: Graph,
      strategy: PatchStrategy,
      isWorthRetry: (Throwable => Boolean) = defaultWorthRetry
  ): F[Unit] =
    bulk(Seq(SparqlWriteQuery.patch(graph, data, strategy)), isWorthRetry)

  /**
    * Executes the replace query that drops the graph identified by the argument ''uri'' from the store.
    *
    * @param graph        the graph to drop
    * @param isWorthRetry a function to decide if it is needed to retry
    */
  def drop(graph: Uri, isWorthRetry: (Throwable => Boolean) = defaultWorthRetry): F[Unit] =
    bulk(Seq(SparqlWriteQuery.drop(graph)), isWorthRetry)

}
