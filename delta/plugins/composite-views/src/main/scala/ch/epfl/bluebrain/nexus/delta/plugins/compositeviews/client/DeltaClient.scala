package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.persistence.query.{NoOffset, Offset, Sequence, TimeBasedUUID}
import akka.stream.alpakka.sse.scaladsl.EventSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger
import io.circe.Decoder
import io.circe.parser.decode
import fs2._
import monix.bio.{IO, Task}
import monix.execution.Scheduler
import streamz.converter._

import java.util.UUID
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
  * A client to request a remote delta instance
  */
final class DeltaClient(client: HttpClient, retryDelay: FiniteDuration)(implicit
    as: ActorSystem[Nothing],
    scheduler: Scheduler
) {

  /**
    * Fetches the [[ProjectCount]] for the remote source
    */
  def projectCount(source: RemoteProjectSource): HttpResult[ProjectCount] = {
    implicit val cred: Option[AuthToken] = token(source)
    val statisticsEndpoint: HttpRequest  =
      Get(
        source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
      ).addHeader(accept).withCredentials
    client.fromJsonTo[ProjectCount](statisticsEndpoint)
  }

  private def toOffset(id: String): Offset =
    Try(TimeBasedUUID(UUID.fromString(id))).orElse(Try(Sequence(id.toLong))).getOrElse(NoOffset)

  def events[A: Decoder](source: RemoteProjectSource, offset: Offset): Stream[Task, (Offset, A)] = {
    val initialLastEventId = offset match {
      case NoOffset             => None
      case Sequence(value)      => Some(value.toString)
      case TimeBasedUUID(value) => Some(value.toString)
    }

    implicit val cred: Option[AuthToken] = token(source)

    def send(request: HttpRequest): Future[HttpResponse] = {
      client[HttpResponse](request.withCredentials)(IO.pure(_)).runToFuture
    }

    val uri =
      source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "events"

    EventSource(uri, send, initialLastEventId, retryDelay)
      .toStream[Task](_ => ())
      .flatMap { sse =>
        val offset = sse.id.map(toOffset).getOrElse(NoOffset)

        decode[A](sse.data) match {
          case Right(event) => Stream.emit(offset -> event)
          case Left(err)    =>
            logger.error(s"Failed to decode sse event '$sse'", err)
            Stream.empty
        }
      }
  }

  /**
    * Fetches a resource with a given id in n-quads format.
    */
  def resourceAsNQuads(source: RemoteProjectSource, id: Iri): HttpResult[NQuads] = {
    implicit val cred: Option[AuthToken] = token(source)
    val req = Get(
      source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "_" / id.toString
    ).addHeader(Accept(RdfMediaTypes.`application/n-quads`)).withCredentials
    client.fromEntityTo[String](req).map(NQuads(_, id))
  }

  private def token(source: RemoteProjectSource) = source.token.map { token => AuthToken(token.value.value) }

}

object DeltaClient {

  private val logger: Logger = Logger[DeltaClient.type]

  private val accept = Accept(`application/json`.mediaType, RdfMediaTypes.`application/ld+json`)

  /**
    * Constructs a delta client
    */
  def apply(client: HttpClient, retryDelay: FiniteDuration)(implicit as: ActorSystem[Nothing], scheduler: Scheduler) =
    new DeltaClient(client, retryDelay)
}
