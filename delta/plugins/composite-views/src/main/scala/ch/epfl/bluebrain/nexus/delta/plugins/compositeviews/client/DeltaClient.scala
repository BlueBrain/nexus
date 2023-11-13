package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.{Get, Head}
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, Accept}
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import akka.stream.alpakka.sse.scaladsl.EventSource
import cats.effect.{ContextShift, IO}
import cats.implicits.{catsSyntaxApplicativeError, catsSyntaxFlatMapOps}
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import io.circe.parser.decode
import fs2._

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/**
  * Collection of functions for interacting with a remote delta instance.
  */
trait DeltaClient {

  /**
    * Fetches the [[ProjectStatistics]] for the remote source
    */
  def projectStatistics(source: RemoteProjectSource): IO[ProjectStatistics]

  /**
    * Fetches the [[RemainingElems]] for the remote source
    */
  def remaining(source: RemoteProjectSource, offset: Offset): IO[RemainingElems]

  /**
    * Checks whether the events endpoint and token provided by the source are correct
    *
    * @param source
    *   the source
    */
  def checkElems(source: RemoteProjectSource): IO[Unit]

  /**
    * Produces a stream of elems with their offset for the provided ''source''.
    *
    * @param source
    *   the remote source that is used to collect the server sent events
    * @param run
    *   the branch run we want to stream for
    * @param offset
    *   the initial offset
    */
  def elems(source: RemoteProjectSource, run: CompositeBranch.Run, offset: Offset): ElemStream[Unit]

  /**
    * Fetches a resource with a given id in n-quads format.
    */
  def resourceAsNQuads(source: RemoteProjectSource, id: Iri): IO[Option[NQuads]]
}

object DeltaClient {

  private val logger = Logger[DeltaClient.type]

  private val accept = Accept(`application/json`.mediaType, RdfMediaTypes.`application/ld+json`)

  final private class DeltaClientImpl(
      client: HttpClient,
      authTokenProvider: AuthTokenProvider,
      credentials: Credentials,
      retryDelay: FiniteDuration
  )(implicit
      as: ActorSystem[Nothing],
      c: ContextShift[IO]
  ) extends DeltaClient {

    override def projectStatistics(source: RemoteProjectSource): IO[ProjectStatistics] = {
      for {
        authToken <- authTokenProvider(credentials)
        request    =
          Get(
            source.endpoint / "projects" / source.project.organization.value / source.project.project.value / "statistics"
          ).addHeader(accept).withCredentials(authToken)
        result    <- client.fromJsonTo[ProjectStatistics](request)
      } yield {
        result
      }
    }

    override def remaining(source: RemoteProjectSource, offset: Offset): IO[RemainingElems] = {
      for {
        authToken <- authTokenProvider(credentials)
        request    = Get(elemAddress(source) / "remaining")
                       .addHeader(accept)
                       .addHeader(`Last-Event-ID`(offset.value.toString))
                       .withCredentials(authToken)
        result    <- client.fromJsonTo[RemainingElems](request)
      } yield result
    }

    override def checkElems(source: RemoteProjectSource): IO[Unit] = {
      for {
        authToken <- authTokenProvider(credentials)
        result    <- client(Head(elemAddress(source)).withCredentials(authToken)) {
                       case resp if resp.status.isSuccess() => IO.delay(resp.discardEntityBytes()) >> IO.unit
                     }
      } yield result
    }

    override def elems(source: RemoteProjectSource, run: CompositeBranch.Run, offset: Offset): ElemStream[Unit] = {
      val initialLastEventId = offset match {
        case Start            => None
        case Offset.At(value) => Some(value.toString)
      }

      def send(request: HttpRequest): Future[HttpResponse] = {
        (for {
          authToken <- authTokenProvider(credentials)
          result    <- client[HttpResponse](request.withCredentials(authToken))(IO.pure(_))
        } yield result).unsafeToFuture()
      }

      val suffix = run match {
        case CompositeBranch.Run.Main    => "continuous"
        case CompositeBranch.Run.Rebuild => "currents"
      }
      val uri    = elemAddress(source) / suffix
      StreamConverter(EventSource(uri, send, initialLastEventId, retryDelay))
        .flatMap { sse =>
          decode[Elem[Unit]](sse.data) match {
            case Right(elem) => Stream.emit(elem)
            case Left(err)   =>
              Stream.eval(logger.error(err)(s"Failed to decode sse event '$sse'")) >>
                Stream.empty
          }
        }
    }

    private def typeQuery(types: Set[Iri])               =
      if (types.isEmpty) Query.Empty
      else Query(types.map(t => "type" -> t.toString).toList: _*)

    private def elemAddress(source: RemoteProjectSource) =
      (source.endpoint / "elems" / source.project.organization.value / source.project.project.value)
        .withQuery(Query("tag" -> source.selectFilter.tag.toString))
        .withQuery(typeQuery(source.selectFilter.types))

    override def resourceAsNQuads(source: RemoteProjectSource, id: Iri): IO[Option[NQuads]] = {
      val resourceUrl =
        source.endpoint / "resources" / source.project.organization.value / source.project.project.value / "_" / id.toString
      for {
        authToken <- authTokenProvider(credentials)
        req        = Get(
                       source.resourceTag.fold(resourceUrl)(t => resourceUrl.withQuery(Query("tag" -> t.value)))
                     ).addHeader(Accept(RdfMediaTypes.`application/n-quads`)).withCredentials(authToken)
        result    <- client.fromEntityTo[String](req).map[Option[NQuads]](nq => Some(NQuads(nq, id))).recover {
                       case HttpClientStatusError(_, StatusCodes.NotFound, _) => None
                     }
      } yield result
    }
  }

  /**
    * Factory method for delta clients.
    */
  def apply(
      client: HttpClient,
      authTokenProvider: AuthTokenProvider,
      credentials: Credentials,
      retryDelay: FiniteDuration
  )(implicit
      as: ActorSystem[Nothing],
      c: ContextShift[IO]
  ): DeltaClient =
    new DeltaClientImpl(client, authTokenProvider, credentials, retryDelay)
}
