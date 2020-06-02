package ch.epfl.bluebrain.nexus.commons.sparql.client

import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.stream.Materializer
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.{withUnmarshaller, UntypedHttpClient}
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig

import scala.concurrent.ExecutionContext

/**
  * A client that exposes additional functions on top of [[ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlClient]]
  * that are specific to Blazegraph.
  *
  * @param base        the base uri of the blazegraph endpoint
  * @param namespace   the namespace that this client targets
  * @param credentials the credentials to use when communicating with the sparql endpoint
  */
class BlazegraphClient[F[_]: Timer](base: Uri, namespace: String, credentials: Option[HttpCredentials])(
    implicit retryConfig: RetryStrategyConfig,
    F: Effect[F],
    cl: UntypedHttpClient[F],
    rsJson: HttpClient[F, SparqlResults],
    serviceDescClient: HttpClient[F, ServiceDescription],
    ec: ExecutionContext
) extends HttpSparqlClient[F](s"$base/namespace/$namespace/sparql", credentials) {

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: F[ServiceDescription] =
    serviceDescClient(Get(s"$base/status"))

  /**
    * Change the retry policy of the current BlazegraphClient.
    *
    * @param config the configuration that instantiates the policy
    * @return a new [[BlazegraphClient]] with the passed retry policy
    */
  def withRetryPolicy(config: RetryStrategyConfig): BlazegraphClient[F] = {
    implicit val retryConfig: RetryStrategyConfig = config
    new BlazegraphClient(base, namespace, credentials)
  }

  /**
    * @param base        the base uri of the blazegraph endpoint
    * @param namespace   the namespace that this client targets
    * @param credentials the credentials to use when communicating with the sparql endpoint
    * @return a new [[BlazegraphClient]] with the provided parameters
    */
  def copy(
      base: Uri = this.base,
      namespace: String = this.namespace,
      credentials: Option[HttpCredentials] = this.credentials
  ): BlazegraphClient[F] =
    new BlazegraphClient[F](base, namespace, credentials)

  /**
    * Check whether the target namespace exists.
    */
  def namespaceExists: F[Boolean] = {
    val req = Get(s"$base/namespace/$namespace")
    cl(addCredentials(req))
      .handleErrorWith(handleError(req, "namespaceExists"))
      .flatMap { resp =>
        resp.status match {
          case StatusCodes.OK       => cl.discardBytes(resp.entity).map(_ => true)
          case StatusCodes.NotFound => cl.discardBytes(resp.entity).map(_ => false)
          case _                    => error(req, resp, "get namespace")
        }
      }
  }

  /**
    * Attempts to create a namespace recovering gracefully when the namespace already exists.
    *
    * @param properties the properties to use for namespace creation
    * @return ''true'' wrapped in ''F'' when namespace has been created and ''false'' wrapped in ''F'' when it already existed
    */
  def createNamespace(properties: Map[String, String]): F[Boolean] = {
    val updated = properties + ("com.bigdata.rdf.sail.namespace" -> namespace)
    val payload = updated.map { case (key, value) => s"$key=$value" }.mkString("\n")
    val req     = Post(s"$base/namespace", HttpEntity(payload))
    cl(addCredentials(req)).handleErrorWith(handleError(req, "createNamespace")).flatMap { resp =>
      resp.status match {
        case StatusCodes.Created  => cl.discardBytes(resp.entity).map(_ => true)
        case StatusCodes.Conflict => cl.discardBytes(resp.entity).map(_ => false)
        case _                    => error(req, resp, "create namespace")
      }
    }
  }

  /**
    * Attempts to delete a namespace recovering gracefully when the namespace does not exists.
    *
    * @return ''true'' wrapped in ''F'' when namespace has been deleted and ''false'' wrapped in ''F'' when it does not existe
    */
  def deleteNamespace: F[Boolean] = {
    val req = Delete(s"$base/namespace/$namespace")
    cl(addCredentials(req)).handleErrorWith(handleError(req, "deleteNamespace")).flatMap { resp =>
      resp.status match {
        case StatusCodes.OK       => cl.discardBytes(resp.entity).map(_ => true)
        case StatusCodes.NotFound => cl.discardBytes(resp.entity).map(_ => false)
        case _                    => error(req, resp, "delete namespace")
      }
    }
  }
}

object BlazegraphClient {
  def apply[F[_]: Effect: Timer](base: Uri, namespace: String, credentials: Option[HttpCredentials])(
      implicit retryConfig: RetryStrategyConfig,
      mt: Materializer,
      cl: UntypedHttpClient[F],
      rsJson: HttpClient[F, SparqlResults],
      ec: ExecutionContext
  ): BlazegraphClient[F] = {
    implicit val serviceDescClient: HttpClient[F, ServiceDescription] = withUnmarshaller[F, ServiceDescription]
    new BlazegraphClient[F](base, namespace, credentials)
  }
}
