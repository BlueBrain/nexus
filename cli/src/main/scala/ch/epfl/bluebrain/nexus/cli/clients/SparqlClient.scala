package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.ClientErrOr
import ch.epfl.bluebrain.nexus.cli.config.EnvConfig
import ch.epfl.bluebrain.nexus.cli.sse.{OrgLabel, ProjectLabel}
import ch.epfl.bluebrain.nexus.cli.utils.Logging._
import io.chrisdavenport.log4cats.Logger
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import retry.CatsEffect._
import retry.syntax.all._
import retry.{RetryDetails, RetryPolicy}

trait SparqlClient[F[_]] {

  /**
    * Performs a SPARQL query on the default view of the passed organization and project.
    *
    * @param org      the organization label
    * @param proj     the project label
    * @param queryStr the SPARQL query
    */
  def query(org: OrgLabel, proj: ProjectLabel, queryStr: String): F[ClientErrOr[SparqlResults]] =
    query(org, proj, None, queryStr)

  /**
    * Performs a SPARQL query on the passed view, organization and project.
    *
    * @param org      the organization label
    * @param proj     the project label
    * @param view     the view @id value
    * @param queryStr the SPARQL query
    */
  def query(org: OrgLabel, proj: ProjectLabel, view: Uri, queryStr: String): F[ClientErrOr[SparqlResults]] =
    query(org, proj, Some(view), queryStr)

  /**
    * Performs a SPARQL query on the passed (or default) view, organization and project.
    *
    * @param org      the organization label
    * @param proj     the project label
    * @param view     the view @id value
    * @param queryStr the SPARQL query
    */
  def query(org: OrgLabel, proj: ProjectLabel, view: Option[Uri], queryStr: String): F[ClientErrOr[SparqlResults]]
}

object SparqlClient {

  /**
    * Construct a [[SparqlClient]] to perform sparql queries using the Nexus API.
    *
    * @param client the underlying HTTP client
    * @param env    the CLI environment configuration
    */
  final def apply[F[_]: Sync: Timer](client: Client[F], env: EnvConfig): SparqlClient[F] =
    new LiveSparqlClient[F](client, env)

  final val `application/sparql-query`: MediaType =
    new MediaType("application", "sparql-query")

  final private class LiveSparqlClient[F[_]: Sync: Timer](client: Client[F], env: EnvConfig) extends SparqlClient[F] {
    private val retry                                = env.httpClient.retry
    private val successCondition                     = retry.condition.notRetryFromEither[SparqlResults] _
    implicit private val retryPolicy: RetryPolicy[F] = retry.retryPolicy
    implicit private val logOnError: (ClientErrOr[SparqlResults], RetryDetails) => F[Unit] =
      (eitherErr, details) => Logger[F].info(s"Sparql client error '$eitherErr'. Retry details: '$details'")

    override def query(
        org: OrgLabel,
        proj: ProjectLabel,
        view: Option[Uri],
        queryStr: String
    ): F[ClientErrOr[SparqlResults]] = {
      val uri     = env.sparql(org, proj, view.getOrElse(env.defaultSparqlView))
      val headers = Headers(env.authorizationHeader.toList)
      val req = Request[F](method = Method.POST, uri = uri, headers = headers)
        .withEntity(queryStr)
        .withContentType(`Content-Type`(`application/sparql-query`))
      val resp: F[ClientErrOr[SparqlResults]] = client.fetch(req)(ClientError.errorOr { r =>
        r.attemptAs[SparqlResults].value.map(_.leftMap(err => SerializationError(err.message, "SparqlResults")))
      })
      resp.retryingM(successCondition)
    }
  }
}
