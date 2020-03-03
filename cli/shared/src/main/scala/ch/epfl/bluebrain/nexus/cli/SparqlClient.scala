package ch.epfl.bluebrain.nexus.cli

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.ClientError.SerializationError
import ch.epfl.bluebrain.nexus.cli.config.{NexusConfig, NexusEndpoints}
import ch.epfl.bluebrain.nexus.cli.types.{Label, SparqlResults}
import io.chrisdavenport.log4cats.Logger
import org.http4s._
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import retry.{RetryDetails, RetryPolicy}
import retry.CatsEffect._
import retry._
import retry.syntax.all._

trait SparqlClient[F[_]] {

  /**
    * Performs a SPARQL query on the default view of the passed organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, value: String): F[ClientErrOr[SparqlResults]] =
    query(organization, project, SparqlClient.defaultSparqlView, value)

  /**
    * Performs a SPARQL query on the passed view, organization and project.
    *
    * @param organization the organization label
    * @param project      the project label
    * @param viewId       the view @id value
    * @param value        the SPARQL query
    */
  def query(organization: Label, project: Label, viewId: Uri, value: String): F[ClientErrOr[SparqlResults]]
}

object SparqlClient {

  final val defaultSparqlView: Uri =
    Uri.unsafeFromString("https://bluebrain.github.io/nexus/vocabulary/defaultSparqlIndex")

  final val `application/sparql-query`: MediaType =
    new MediaType("application", "sparql-query")

  /**
    * Construct a [[SparqlClient]] to query Nexus sparql view.
    *
    * @param client the underlying HTTP client
    * @param config the Nexus configuration
    * @tparam F the effect type
    */
  final def apply[F[_]](
      client: Client[F],
      config: NexusConfig
  )(implicit F: Sync[F], T: Timer[F]): SparqlClient[F] =
    new SparqlClient[F] {

      private val endpoints                            = NexusEndpoints(config)
      private val retryCondition                       = config.retry.retryCondition.fromEither[SparqlResults] _
      private implicit val retryPolicy: RetryPolicy[F] = config.retry.retryPolicy
      private implicit val logOnError: (ClientErrOr[SparqlResults], RetryDetails) => F[Unit] =
        (eitherErr, details) => Logger[F].info(s"Client error '$eitherErr'. Retry details: '$details'")

      def query(
          organization: Label,
          project: Label,
          viewId: Uri,
          value: String
      ): F[ClientErrOr[SparqlResults]] = {
        val uri     = endpoints.sparqlQueryUri(organization, project, viewId)
        val headers = Headers(config.authorizationHeader.toList)
        val req = Request[F](method = Method.POST, uri = uri, headers = headers)
          .withEntity(value)
          .withContentType(`Content-Type`(`application/sparql-query`))
        val resp: F[ClientErrOr[SparqlResults]] = client.fetch(req)(ClientError.errorOr { r =>
          r.attemptAs[SparqlResults].value.map(_.leftMap(err => SerializationError(err.message)))
        })
        resp.retryingM(retryCondition)
      }
    }
}
