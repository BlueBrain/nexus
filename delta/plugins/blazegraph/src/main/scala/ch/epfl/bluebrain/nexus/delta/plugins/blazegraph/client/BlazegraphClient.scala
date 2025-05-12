package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient.timeoutHeader
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{InvalidCountRequest, SparqlActionError, SparqlQueryError, SparqlWriteError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.{Aux, SparqlResultsJson}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.NamespaceProperties
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import org.http4s.Method.{DELETE, GET, POST}
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.{EntityDecoder, Header, MediaType, Status, Uri, UrlForm}
import org.typelevel.ci.CIString

import scala.concurrent.duration.*
import scala.reflect.ClassTag

/**
  * A client that exposes additional functions on top of [[SparqlClient]] that are specific to Blazegraph.
  */
final class BlazegraphClient(client: Client[IO], endpoint: Uri, queryTimeout: Duration) extends SparqlClient {

  private val serviceVersion = """(buildVersion">)([^<]*)""".r
  private val serviceName    = "blazegraph"

  private def queryEndpoint(namespace: String): Uri = endpoint / "namespace" / namespace / "sparql"

  private def updateEndpoint(namespace: String): Uri = queryEndpoint(namespace)

  override protected def queryRequest[A](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[Header.ToRaw]
  )(implicit entityDecoder: EntityDecoder[IO, A], classTag: ClassTag[A]): IO[A] = {
    val request = POST(queryEndpoint(namespace), accept(mediaTypes)).withEntity(UrlForm("query" -> q.value))
    client.expectOr[A](request)(SparqlQueryError(_))
  }

  override def query[R <: SparqlQueryResponse](
      namespaces: Iterable[String],
      q: SparqlQuery,
      responseType: Aux[R],
      additionalHeaders: Seq[Header.ToRaw]
  ): IO[R] = {
    val timeout: Option[Header.ToRaw] =
      Option.when(queryTimeout.isFinite)(Header.Raw(timeoutHeader, queryTimeout.toMillis.toString))
    val headers                       = additionalHeaders ++ timeout
    super.query(namespaces, q, responseType, headers)
  }

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[ServiceDescription] =
    client
      .expect[ResolvedServiceDescription](endpoint / "status")
      .timeout(1.second)
      .recover(_ => ServiceDescription.unresolved(serviceName))

  override def existsNamespace(namespace: String): IO[Boolean] =
    client.statusFromUri(endpoint / "namespace" / namespace).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(SparqlActionError(status, "exists"))
    }

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
        val request       = POST(endpoint / "namespace").withEntity(withNamespace.toString)
        client.status(request).flatMap {
          case Status.Created  => IO.pure(true)
          case Status.Conflict => IO.pure(false)
          case Status.NotFound => IO.pure(false)
          case status          => IO.raiseError(SparqlActionError(status, "create"))
        }
    }

  override def createNamespace(namespace: String): IO[Boolean] =
    createNamespace(namespace, NamespaceProperties.defaultValue)

  override def deleteNamespace(namespace: String): IO[Boolean] = {
    val request = DELETE(endpoint / "namespace" / namespace)
    client.status(request).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(SparqlActionError(status, "delete"))
    }
  }

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
    val describeEndpoint   = (endpoint / "namespace").withQueryParam("describe-each-named-graph", "false")
    val request            = GET(describeEndpoint, accept(SparqlResultsJson.mediaTypes))
    import org.http4s.circe.CirceEntityDecoder.*
    client.expect[SparqlResults](request).map { response =>
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

  override def bulk(namespace: String, queries: Seq[SparqlWriteQuery]): IO[Unit] =
    IO.fromEither(SparqlBulkUpdate(namespace, queries)).flatMap { bulk =>
      val form     = UrlForm("update" -> bulk.queryString)
      val endpoint = updateEndpoint(namespace).copy(query = bulk.queryParams)
      val request  = POST(endpoint, accept(SparqlResultsJson.mediaTypes)).withEntity(form)
      client.expectOr[Unit](request)(SparqlWriteError(_)).void
    }

  implicit private val resolvedServiceDescriptionDecoder: EntityDecoder[IO, ResolvedServiceDescription] =
    EntityDecoder.text[IO].map {
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
  val timeoutHeader: CIString = CIString("X-BIGDATA-MAX-QUERY-MILLIS")
}
