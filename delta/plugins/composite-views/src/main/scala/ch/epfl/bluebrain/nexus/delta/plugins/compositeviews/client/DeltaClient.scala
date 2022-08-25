package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Get, Head}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.stream.alpakka.sse.scaladsl.EventSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.AuthToken
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import com.typesafe.scalalogging.Logger
import io.circe.Decoder
import io.circe.parser.decode
import fs2._
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler
import streamz.converter._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
  * Collection of functions for interacting with a remote delta instance.
  */
trait DeltaClient {

  /**
    * Fetches the [[ProjectStatistics]] for the remote source
    */
  def projectCount(source: RemoteProjectSource): HttpResult[ProjectStatistics]

  /**
    * Checks whether the events endpoint and token provided by the source are correct
    *
    * @param source
    *   the source
    */
  def checkEvents(source: RemoteProjectSource): HttpResult[Unit]

  /**
    * Produces a stream of events with their offset for the provided ''source''.
    *
    * @param source
    *   the remote source that is used to collect the server sent events
    * @param offset
    *   the initial offset
    */
  def events[A: Decoder](source: RemoteProjectSource, offset: Offset): Stream[Task, (Offset, A)]

  /**
    * Fetches a resource with a given id in n-quads format.
    */
  def resourceAsNQuads(source: RemoteProjectSource, id: Iri, tag: Option[UserTag]): HttpResult[Option[NQuads]]

}

object DeltaClient {

  private val logger: Logger = Logger[DeltaClient.type]

  private val accept = Accept(`application/json`.mediaType, RdfMediaTypes.`application/ld+json`)

  final private class DeltaClientImpl(client: HttpClient, retryDelay: FiniteDuration)(implicit
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ) extends DeltaClient {

    override def projectCount(source: RemoteProjectSource): HttpResult[ProjectStatistics] = {
      implicit val cred: Option[AuthToken] = token(source)
      val statisticsEndpoint: HttpRequest  =
        Get(
          source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
        ).addHeader(accept).withCredentials
      client.fromJsonTo[ProjectStatistics](statisticsEndpoint)
    }

    override def checkEvents(source: RemoteProjectSource): HttpResult[Unit] = {
      val uri                              =
        source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "events"
      implicit val cred: Option[AuthToken] = token(source)
      client(Head(uri).withCredentials) {
        case resp if resp.status.isSuccess() => UIO.delay(resp.discardEntityBytes()) >> IO.unit
      }
    }

    override def events[A: Decoder](source: RemoteProjectSource, offset: Offset): Stream[Task, (Offset, A)] = {
      val initialLastEventId = offset match {
        case Start            => None
        case Offset.At(value) => Some(value.toString)
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
          val offset = sse.id.map(toOffset).getOrElse(Start)

          decode[A](sse.data) match {
            case Right(event) => Stream.emit(offset -> event)
            case Left(err)    =>
              logger.error(s"Failed to decode sse event '$sse'", err)
              Stream.empty
          }
        }
    }

    override def resourceAsNQuads(
        source: RemoteProjectSource,
        id: Iri,
        tag: Option[UserTag]
    ): HttpResult[Option[NQuads]] = {
      implicit val cred: Option[AuthToken] = token(source)
      val resourceUrl: Uri                 =
        source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "_" / id.toString
      val req                              = Get(
        tag.fold(resourceUrl)(t => resourceUrl.withQuery(Query("tag" -> t.value)))
      ).addHeader(Accept(RdfMediaTypes.`application/n-quads`)).withCredentials
      client.fromEntityTo[String](req).map(nq => Some(NQuads(nq, id))).onErrorRecover {
        case HttpClientStatusError(_, StatusCodes.NotFound, _) => None
      }
    }

    private def token(source: RemoteProjectSource) =
      source.token.map { token => AuthToken(token.value.value) }

    private def toOffset(id: String): Offset =
      id.toLongOption.map(Offset.at).getOrElse(Start)
  }

  /**
    * Factory method for delta clients.
    */
  def apply(client: HttpClient, retryDelay: FiniteDuration)(implicit
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): DeltaClient =
    new DeltaClientImpl(client, retryDelay)
}
