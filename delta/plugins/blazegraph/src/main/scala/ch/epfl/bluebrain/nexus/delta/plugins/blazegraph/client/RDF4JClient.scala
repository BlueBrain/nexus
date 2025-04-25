package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.RdfHttp4sMediaTypes.*
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.utils.Handlebars
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.{SparqlActionError, SparqlQueryError}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlResultsJson
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery
import org.http4s.Method.{DELETE, GET, POST, PUT}
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.http4s.{EntityDecoder, Header, MediaType, Status, Uri}

import scala.reflect.ClassTag

final class RDF4JClient(client: Client[IO], endpoint: Uri, repositoryTemplate: String) extends SparqlClient {

  private val serviceName = "rdf4j"

  private val repositoriesEndpoint = endpoint / "repositories"

  private def queryEndpoint(namespace: String): Uri = repositoriesEndpoint / namespace

  private def updateEndpoint(namespace: String): Uri = queryEndpoint(namespace) / "statements"

  override def serviceDescription: IO[ServiceDescription] = IO.pure(ServiceDescription.unresolved(serviceName))

  override def existsNamespace(namespace: String): IO[Boolean] = {
    client.statusFromUri(repositoriesEndpoint / namespace / "size").flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(SparqlActionError(status, "exists"))
    }
  }

  override def createNamespace(namespace: String): IO[Boolean] = {
    val payload = Handlebars(repositoryTemplate, "namespace" -> namespace)
    val request = PUT(payload, repositoriesEndpoint / namespace, `Content-Type`(`text/turtle`))
    client.status(request).flatMap {
      case Status.NoContent => IO.pure(true)
      case Status.Conflict  => IO.pure(false)
      case status           => IO.raiseError(SparqlActionError(status, "create"))
    }
  }

  override def deleteNamespace(namespace: String): IO[Boolean] = {
    val request = DELETE(repositoriesEndpoint / namespace)
    client.status(request).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(SparqlActionError(status, "delete"))
    }
  }

  override def listNamespaces: IO[Vector[String]] = {
    val request = GET(repositoriesEndpoint, accept(SparqlResultsJson.mediaTypes))
    import org.http4s.circe.CirceEntityDecoder.*
    client.expect[SparqlResults](request).map { response =>
      response.results.bindings.foldLeft(Vector.empty[String]) { case (acc, binding) =>
        val namespaceName = binding.get("id").map(_.value)
        acc ++ namespaceName
      }
    }
  }

  override def count(namespace: String): IO[Long] =
    client
      .expect[String](repositoriesEndpoint / namespace / "size")
      .map(_.toLongOption.getOrElse(-1L))

  override protected def queryRequest[A](
      namespace: String,
      q: SparqlQuery,
      mediaTypes: NonEmptyList[MediaType],
      additionalHeaders: Seq[Header.ToRaw]
  )(implicit entityDecoder: EntityDecoder[IO, A], classTag: ClassTag[A]): IO[A] = {
    val contentType = `Content-Type`(`application/sparql-query`)
    val request     = POST(q.value, queryEndpoint(namespace), accept(mediaTypes), contentType)
    client.expectOr[A](request)(SparqlQueryError(_))
  }

  override def bulk(namespace: String, queries: Seq[SparqlWriteQuery]): IO[Unit] =
    IO.fromEither(SparqlBulkUpdate(namespace, queries)).flatMap { bulk =>
      val endpoint    = updateEndpoint(namespace).copy(query = bulk.queryParams)
      val contentType = `Content-Type`(`application/sparql-update`)
      val request     = POST(bulk.queryString, endpoint, accept(SparqlResultsJson.mediaTypes), contentType)
      client.expectOr[Unit](request)(SparqlQueryError(_)).void
    }
}

object RDF4JClient {

  private val lmdbRepositoryTemplate = """@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>.
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

  def lmdb(client: Client[IO], endpoint: Uri) = new RDF4JClient(client, endpoint, lmdbRepositoryTemplate)

}
