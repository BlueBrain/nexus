package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Post}
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, HttpCredentials}
import akka.http.scaladsl.model.{HttpEntity, Uri}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.unmarshalling.PredefinedFromEntityUnmarshallers.stringUnmarshaller
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClientError.WrappedHttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.IO

/**
  * A client that exposes additional functions on top of [[SparqlClient]] that are specific to Blazegraph.
  */
class BlazegraphClient(
    client: HttpClient,
    endpoint: Uri
)(implicit credentials: Option[HttpCredentials], as: ActorSystem)
    extends SparqlClient(client, SparqlQueryEndpoint.blazegraph(endpoint)) {

  private val serviceVersion = """(buildVersion">)([^<]*)""".r

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[SparqlClientError, ServiceDescription] =
    client.fromEntityTo[ServiceDescription](Get(endpoint / "status")).mapError(WrappedHttpClientError)

  /**
    * Check whether the passed namespace ''index'' exists.
    */
  def existsNamespace(index: String): IO[SparqlClientError, Boolean] =
    client(Get(endpoint / "namespace" / index)) {
      case resp if resp.status == OK       => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(true)
      case resp if resp.status == NotFound => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(false)
    }.mapError(WrappedHttpClientError)

  /**
    * Attempts to create a namespace recovering gracefully when the namespace already exists.
    *
    * @param index the namespace
    * @param properties the properties to use for namespace creation
    * @return ''true'' wrapped on an IO when namespace has been created and ''false'' wrapped on an IO when it already existed
    */
  def createNamespace(index: String, properties: Map[String, String]): IO[SparqlClientError, Boolean] = {
    val updated = properties + ("com.bigdata.rdf.sail.namespace" -> index)
    val payload = updated.map { case (key, value) => s"$key=$value" }.mkString("\n")
    val req     = Post(endpoint / "namespace", HttpEntity(payload))
    client(req) {
      case resp if resp.status.isSuccess() => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(true)
      case resp if resp.status == Conflict => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(false)
    }.mapError(WrappedHttpClientError)
  }

  /**
    * Attempts to delete a namespace recovering gracefully when the namespace does not exists.
    *
    * @return ''true'' wrapped in ''F'' when namespace has been deleted and ''false'' wrapped in ''F'' when it does not existe
    */
  def deleteNamespace(index: String): IO[SparqlClientError, Boolean] =
    client(Delete(endpoint / "namespace" / index)) {
      case resp if resp.status == OK       => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(true)
      case resp if resp.status == NotFound => IO.delay(resp.discardEntityBytes()).hideErrors >> IO.pure(false)
    }.mapError(WrappedHttpClientError)

  implicit private val serviceDescDecoder: FromEntityUnmarshaller[ServiceDescription] = stringUnmarshaller.map {
    serviceVersion.findFirstMatchIn(_).map(_.group(2)) match {
      case None          => throw new IllegalArgumentException(s"'version' not found using regex $serviceVersion")
      case Some(version) => ServiceDescription("blazegraph", version)
    }
  }

}

object BlazegraphClient {

  type Credentials = Option[(String, Secret[String])]

  /**
    * Construct a [[BlazegraphClient]]
    */
  def apply(
      client: HttpClient,
      endpoint: Uri,
      credentials: Credentials
  )(implicit as: ActorSystem): BlazegraphClient = {
    implicit val cred: Option[BasicHttpCredentials] =
      credentials.map { case (user, pass) => BasicHttpCredentials(user, pass.value) }
    new BlazegraphClient(client, endpoint)
  }
}
