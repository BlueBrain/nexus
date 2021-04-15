package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.{HttpRequest, StatusCodes}
import akka.http.scaladsl.model.headers.Accept
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient.accept
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.{IO, UIO}

/**
  * A client to request a remote delta instance
  */
final class DeltaClient(client: HttpClient) {

  /**
    * Fetches the [[ProjectCount]] for the remote source
    */
  def projectCount(source: RemoteProjectSource): IO[HttpClientError, Option[ProjectCount]] = {
    implicit val cred: Option[AuthToken] = source.token.map { token => AuthToken(token.value.value) }
    val statisticsEndpoint: HttpRequest  =
      Get(
        source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
      ).addHeader(accept).withCredentials
    client.fromJsonTo[ProjectCount](statisticsEndpoint).map(Some.apply).onErrorHandleWith {
      case HttpClientStatusError(_, StatusCodes.NotFound, _) => UIO.pure(None)
      case err                                               => IO.raiseError(err)
    }
  }

}

object DeltaClient {

  private val accept = Accept(`application/json`.mediaType, RdfMediaTypes.`application/ld+json`)

  /**
    * Constructs a delta client
    */
  def apply(client: HttpClient) =
    new DeltaClient(client)
}
