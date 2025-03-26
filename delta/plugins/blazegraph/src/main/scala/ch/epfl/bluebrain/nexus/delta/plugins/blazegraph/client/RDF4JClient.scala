package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.HttpCredentials
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.kernel.RdfMediaTypes.`application/sparql-query`
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.Handlebars
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.WrappedHttpClientError
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlResultsJson
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

import scala.reflect.ClassTag

final class RDF4JClient(client: HttpClient, endpoint: Uri, repositoryTemplate: String)(implicit
    credentials: Option[HttpCredentials],
    as: ActorSystem
) extends SparqlClient {

  import as.dispatcher

  private val serviceName = "rdf4j"

  private val repositoriesEndpoint = endpoint / "repositories"

  private def queryEndpoint(namespace: String): Uri = repositoriesEndpoint / namespace

  private def updateEndpoint(namespace: String): Uri = queryEndpoint(namespace) / "statements"

  override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription.unresolved(serviceName))

  override def existsNamespace(namespace: String): IO[Boolean] =
    client
      .run(Get(repositoriesEndpoint / namespace / "size")) {
        case resp if resp.status == OK       => IO.delay(resp.discardEntityBytes()).as(true)
        case resp if resp.status == NotFound => IO.delay(resp.discardEntityBytes()).as(false)
      }
      .adaptError { case e: HttpClientError => WrappedHttpClientError(e) }

  override def createNamespace(namespace: String): IO[Boolean] = {
    val payload = Handlebars(repositoryTemplate, "namespace" -> namespace)
    val entity  = HttpEntity(RdfMediaTypes.`text/turtle`, payload)
    val request = Put(repositoriesEndpoint / namespace, entity).withHttpCredentials
    client
      .run(request) {
        case resp if resp.status == NoContent => IO.delay(resp.discardEntityBytes()).as(true)
        case resp if resp.status == Conflict  => IO.delay(resp.discardEntityBytes()).as(false)
      }
      .adaptError { case e: HttpClientError => WrappedHttpClientError(e) }
  }

  override def deleteNamespace(namespace: String): IO[Boolean] = {
    val request = Delete(repositoriesEndpoint / namespace).withHttpCredentials
    client.fromEntityTo[String](request).as(false)
  }

  override def listNamespaces: IO[Vector[String]] = {
    val request = Get(repositoriesEndpoint)
      .withHeaders(accept(SparqlResultsJson.mediaTypes.toList))
      .withHttpCredentials
    client.fromJsonTo[SparqlResults](request).map { response =>
      response.results.bindings.foldLeft(Vector.empty[String]) { case (acc, binding) =>
        val namespaceName = binding.get("id").map(_.value)
        acc ++ namespaceName
      }
    }
  }

  override def count(namespace: String): IO[Long] = {
    val request = Get(repositoriesEndpoint / namespace / "size")
    client.fromEntityTo[String](request).map(_.toLongOption.getOrElse(-1L))
  }

  override protected def queryRequest[A: FromEntityUnmarshaller: ClassTag](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[HttpHeader]
  ): IO[A] = {
    implicit val sparqlQueryMarshaller: ToEntityMarshaller[SparqlQuery] =
      Marshaller.StringMarshaller.wrap(`application/sparql-query`)(_.value)
    val req                                                             = Post(queryEndpoint(namespace), q)
      .withHeaders(accept(mediaTypes.toList), additionalHeaders: _*)
      .withHttpCredentials
    client.fromEntityTo[A](req).adaptError { case e: HttpClientError =>
      WrappedHttpClientError(e)
    }
  }

  override def bulk(namespace: String, queries: Seq[SparqlWriteQuery]): IO[Unit] = {
    for {
      bulk       <- IO.fromEither(SparqlBulkUpdate(namespace, queries))
      entity      = HttpEntity(RdfMediaTypes.`application/sparql-update`, bulk.queryString)
      reqEndpoint = updateEndpoint(namespace).withQuery(bulk.queryParams)
      req         = Post(reqEndpoint, entity).withHttpCredentials
      result     <- client.discardBytes(req, ()).adaptError { case e: HttpClientError => WrappedHttpClientError(e) }
    } yield result
  }
}

object RDF4JClient {

  val lmdbRepositoryTemplate = """@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.
                                          |@prefix config: <tag:rdf4j.org,2023:config/>.
                                          |
                                          |[] a config:Repository ;
                                          |   config:rep.id "{{namespace}}" ;
                                          |   rdfs:label "{{namespace}} LMDB store" ;
                                          |   config:rep.impl [
                                          |      config:rep.type "openrdf:SailRepository" ;
                                          |      config:sail.impl [
                                          |         config:sail.type "rdf4j:LmdbStore" ;
                                          |         config:sail.defaultQueryEvaluationMode "STRICT" ;
                                          |      ]
                                          |   ].""".stripMargin

  def lmdb(client: HttpClient, endpoint: Uri)(implicit credentials: Option[HttpCredentials], as: ActorSystem) =
    new RDF4JClient(client, endpoint, lmdbRepositoryTemplate)

}
