package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.Accept
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient.accept
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import monix.bio.IO

/**
  * A client to request a remote delta instance
  */
final class DeltaClient(client: HttpClient) {

  /**
    * Fetches statistics for the remote source
    */
  def statistics(source: RemoteProjectSource): IO[HttpClientError, ProjectCount] = {
    implicit val cred: Option[AuthToken] = source.token.map { token => AuthToken(token.value.value) }
    val statisticsEndpoint: HttpRequest  =
      Get(
        source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
      ).addHeader(accept).withCredentials
    client.fromJsonTo[ProjectCount](statisticsEndpoint)
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
