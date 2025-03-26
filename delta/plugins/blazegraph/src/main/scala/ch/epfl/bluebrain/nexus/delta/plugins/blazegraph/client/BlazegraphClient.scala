package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Post}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{HttpCredentials, RawHeader}
import akka.http.scaladsl.model.{FormData, HttpEntity, HttpHeader, MediaType, Uri}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.stringUnmarshaller
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient.timeoutHeader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidCountRequest, WrappedHttpClientError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{Aux, SparqlResultsJson}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.NamespaceProperties
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * A client that exposes additional functions on top of [[SparqlClient]] that are specific to Blazegraph.
  */
final class BlazegraphClient(
    client: HttpClient,
    endpoint: Uri,
    queryTimeout: Duration
)(implicit credentials: Option[HttpCredentials], as: ActorSystem)
    extends SparqlClient {

  private val serviceVersion = """(buildVersion">)([^<]*)""".r
  private val serviceName    = "blazegraph"

  import as.dispatcher

  private def queryEndpoint(namespace: String): Uri = endpoint / "namespace" / namespace / "sparql"

  private def updateEndpoint(namespace: String): Uri = queryEndpoint(namespace)

  override protected def queryRequest[A: FromEntityUnmarshaller: ClassTag](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[HttpHeader]
  ): IO[A] = {
    val req = Post(queryEndpoint(namespace), FormData("query" -> q.value))
      .withHeaders(accept(mediaTypes.toList), additionalHeaders: _*)
      .withHttpCredentials
    client.fromEntityTo[A](req).adaptError { case e: HttpClientError =>
      WrappedHttpClientError(e)
    }
  }

  override def query[R <: SparqlQueryResponse](
      namespaces: Iterable[String],
      q: SparqlQuery,
      responseType: Aux[R],
      additionalHeaders: Seq[HttpHeader]
  ): IO[R] = {
    val timeout = Option.when(queryTimeout.isFinite)(RawHeader(timeoutHeader, queryTimeout.toMillis.toString))
    val headers = additionalHeaders ++ timeout
    super.query(namespaces, q, responseType, headers)
  }

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[ServiceDescription] =
    client
      .fromEntityTo[ResolvedServiceDescription](Get(endpoint / "status"))
      .timeout(1.second)
      .redeem(_ => ServiceDescription.unresolved(serviceName), _.copy(name = serviceName))

  override def existsNamespace(namespace: String): IO[Boolean]                         =
    client
      .run(Get(endpoint / "namespace" / namespace)) {
        case resp if resp.status == OK       => IO.delay(resp.discardEntityBytes()).as(true)
        case resp if resp.status == NotFound => IO.delay(resp.discardEntityBytes()).as(false)
      }
      .adaptError { case e: HttpClientError => WrappedHttpClientError(e) }

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
  def createNamespace(namespace: String, properties: NamespaceProperties): IO[Boolean] =
    existsNamespace(namespace).flatMap {
      case true  => IO.pure(false)
      case false =>
        val withNamespace = properties + ("com.bigdata.rdf.sail.namespace", namespace)
        val req           = Post(endpoint / "namespace", HttpEntity(withNamespace.toString))
        client
          .run(req) {
            case resp if resp.status.isSuccess() => IO.delay(resp.discardEntityBytes()).as(true)
            case resp if resp.status == Conflict => IO.delay(resp.discardEntityBytes()).as(false)
          }
          .adaptError { case e: HttpClientError => WrappedHttpClientError(e) }
    }

  override def createNamespace(namespace: String): IO[Boolean] =
    createNamespace(namespace, NamespaceProperties.defaultValue)

  override def deleteNamespace(namespace: String): IO[Boolean] =
    client
      .run(Delete(endpoint / "namespace" / namespace)) {
        case resp if resp.status == OK       => IO.delay(resp.discardEntityBytes()).as(true)
        case resp if resp.status == NotFound => IO.delay(resp.discardEntityBytes()).as(false)
      }
      .adaptError { case e: HttpClientError => WrappedHttpClientError(e) }

  def count(namespace: String): IO[Long] = {
    val sparqlQuery = SparqlConstructQuery.unsafe("SELECT (COUNT(*) AS ?count) WHERE { ?s ?p ?o }")
    query(Set(namespace), sparqlQuery, SparqlResultsJson)
      .flatMap { response =>
        val count = for {
          head          <- response.value.results.bindings.headOption
          countAsString <- head.get("count")
          count         <- countAsString.value.toLongOption
        } yield count

        IO.fromOption(count)(InvalidCountRequest(namespace, sparqlQuery.value))
      }
  }

  /**
    * List all namespaces in the blazegraph instance
    */
  def listNamespaces: IO[Vector[String]] = {
    val namespacePredicate = "http://www.bigdata.com/rdf#/features/KB/Namespace"
    val describeEndpoint   = (endpoint / "namespace").withQuery(Query("describe-each-named-graph" -> "false"))
    val request            = Get(describeEndpoint).withHeaders(accept(SparqlResultsJson.mediaTypes.toList))
    client.fromJsonTo[SparqlResults](request).map { response =>
      response.results.bindings.foldLeft(Vector.empty[String]) { case (acc, binding) =>
        val isNamespace   = binding.get("predicate").exists(_.value == namespacePredicate)
        val namespaceName = binding.get("object").map(_.value)
        if (isNamespace)
          acc ++ namespaceName
        else
          acc
      }
    }
  }

  override def bulk(namespace: String, queries: Seq[SparqlWriteQuery]): IO[Unit] = {
    for {
      bulk       <- IO.fromEither(SparqlBulkUpdate(namespace, queries))
      formData    = FormData("update" -> bulk.queryString)
      reqEndpoint = updateEndpoint(namespace).withQuery(bulk.queryParams)
      req         = Post(reqEndpoint, formData).withHttpCredentials
      result     <- client.discardBytes(req, ()).adaptError { case e: HttpClientError => WrappedHttpClientError(e) }
    } yield result
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
}
