package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Post}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials, RawHeader}
import akka.http.scaladsl.model.{HttpEntity, HttpHeader, Uri}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.stringUnmarshaller
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient.timeoutHeader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidCountRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{Aux, SparqlResultsJson}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig.Credentials
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, UIO}

import scala.concurrent.duration._

/**
  * A client that exposes additional functions on top of [[SparqlClient]] that are specific to Blazegraph.
  */
class BlazegraphClient(
    client: HttpClient,
    endpoint: Uri,
    queryTimeout: Duration
)(implicit credentials: Option[HttpCredentials], as: ActorSystem)
    extends SparqlClient(client, SparqlQueryEndpoint.blazegraph(endpoint)) {

  implicit private val cl: ClassLoader = getClass.getClassLoader

  private val serviceVersion = """(buildVersion">)([^<]*)""".r
  private val serviceName    = Name.unsafe("blazegraph")

  private val defaultProperties =
    ClasspathResourceUtils.bioPropertiesOf("blazegraph/index.properties").hideErrors.memoizeOnSuccess

  override def query[R <: SparqlQueryResponse](
      indices: Iterable[String],
      q: SparqlQuery,
      responseType: Aux[R],
      additionalHeaders: Seq[HttpHeader]
  ): IO[SparqlClientError, R] = {
    val headers = queryTimeout match {
      case finite: FiniteDuration => additionalHeaders :+ RawHeader(timeoutHeader, finite.toMillis.toString)
      case _                      => additionalHeaders
    }
    super.query(indices, q, responseType, headers)
  }

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: UIO[ServiceDescription] =
    client
      .fromEntityTo[ResolvedServiceDescription](Get(endpoint / "status"))
      .timeout(5.seconds)
      .redeem(
        _ => ServiceDescription.unresolved(serviceName),
        _.map(_.copy(name = serviceName)).getOrElse(ServiceDescription.unresolved(serviceName))
      )

  /**
    * Check whether the passed namespace ''namespace'' exists.
    */
  def existsNamespace(namespace: String): IO[SparqlClientError, Boolean] =
    client(Get(endpoint / "namespace" / namespace)) {
      case resp if resp.status == OK       => UIO.delay(resp.discardEntityBytes()) >> IO.pure(true)
      case resp if resp.status == NotFound => UIO.delay(resp.discardEntityBytes()) >> IO.pure(false)
    }.mapError(WrappedHttpClientError)

  /**
    * Attempts to create a namespace (if it doesn't exist) recovering gracefully when the namespace already exists.
    *
    * @param namespace
    *   the namespace
    * @param properties
    *   the properties to use for namespace creation
    * @return
    *   ''true'' wrapped on an IO when namespace has been created and ''false'' wrapped on an IO when it already existed
    */
  def createNamespace(namespace: String, properties: Map[String, String]): IO[SparqlClientError, Boolean] =
    existsNamespace(namespace).flatMap {
      case true  => IO.pure(false)
      case false =>
        val updated = properties + ("com.bigdata.rdf.sail.namespace" -> namespace)
        val payload = updated.map { case (key, value) => s"$key=$value" }.mkString("\n")
        val req     = Post(endpoint / "namespace", HttpEntity(payload))
        client(req) {
          case resp if resp.status.isSuccess() => UIO.delay(resp.discardEntityBytes()) >> IO.pure(true)
          case resp if resp.status == Conflict => UIO.delay(resp.discardEntityBytes()) >> IO.pure(false)
        }.mapError(WrappedHttpClientError)
    }

  /**
    * Attempts to create a namespace (if it doesn't exist) with default properties recovering gracefully when the
    * namespace already exists.
    *
    * @param namespace
    *   the namespace
    * @return
    *   ''true'' wrapped on an IO when namespace has been created and ''false'' wrapped on an IO when it already existed
    */
  def createNamespace(namespace: String): IO[SparqlClientError, Boolean] =
    defaultProperties.flatMap(createNamespace(namespace, _))

  /**
    * Attempts to delete a namespace recovering gracefully when the namespace does not exists.
    *
    * @return
    *   ''true'' wrapped in ''F'' when namespace has been deleted and ''false'' wrapped in ''F'' when it does not existe
    */
  def deleteNamespace(namespace: String): IO[SparqlClientError, Boolean] =
    client(Delete(endpoint / "namespace" / namespace)) {
      case resp if resp.status == OK       => UIO.delay(resp.discardEntityBytes()) >> IO.pure(true)
      case resp if resp.status == NotFound => UIO.delay(resp.discardEntityBytes()) >> IO.pure(false)
    }.mapError(WrappedHttpClientError)

  /**
    * Count all the triples on an index
    */
  def count(index: String): IO[SparqlClientError, Long] = {
    val sparqlQuery = SparqlConstructQuery.unsafe("SELECT (COUNT(?s) AS ?count) WHERE { ?s ?p ?o }")
    query(Set(index), sparqlQuery, SparqlResultsJson)
      .flatMap { response =>
        val count = for {
          head          <- response.value.results.bindings.headOption
          countAsString <- head.get("count")
          count         <- countAsString.value.toLongOption
        } yield count

        IO.fromOption(count, InvalidCountRequest(index, sparqlQuery.value))
      }
  }

  implicit private val resolvedServiceDescriptionDecoder: FromEntityUnmarshaller[ResolvedServiceDescription] =
    stringUnmarshaller.map {
      serviceVersion.findFirstMatchIn(_).map(_.group(2)) match {
        case None          => throw new IllegalArgumentException(s"'version' not found using regex $serviceVersion")
        case Some(version) => ServiceDescription(serviceName, version)
      }
    }

}

object BlazegraphClient {

  /**
    * Blazegraph timeout header.
    */
  val timeoutHeader: String = "X-BIGDATA-MAX-QUERY-MILLIS"

  /**
    * Construct a [[BlazegraphClient]]
    */
  def apply(
      client: HttpClient,
      endpoint: Uri,
      credentials: Option[Credentials],
      queryTimeout: Duration
  )(implicit as: ActorSystem): BlazegraphClient = {
    implicit val cred: Option[BasicHttpCredentials] =
      credentials.map { cred => BasicHttpCredentials(cred.username, cred.password.value) }
    new BlazegraphClient(client, endpoint, queryTimeout)
  }
}
