package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.http.client.ResponseUtils
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RdfHttp4sMediaTypes}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient.RemoteCheck.{RemoteCheckFailed, RemoteCheckTimeout, RemoteUnknownHost}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.{Latest, UserTag}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{IriFilter, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, ElemStream, RemainingElems}
import io.circe.Json
import io.circe.parser.decode
import org.http4s.Method.{GET, HEAD}
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.middleware.GZip
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Accept
import org.http4s.{Header, MediaType, Response, Status, Uri}

import java.net.UnknownHostException
import scala.concurrent.duration.*

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

  private val accept: Header.ToRaw = Accept(MediaType.application.json, RdfHttp4sMediaTypes.`application/ld+json`)

  private val remoteCheckTimeout = 3.seconds

  sealed abstract class RemoteCheckError(val reason: String, val body: Option[Json]) extends SDKError

  object RemoteCheck {

    final case class RemoteCheckFailed(status: Status, override val body: Option[Json])
        extends RemoteCheckError(s"Remote check failed with status $status", body)

    object RemoteCheckFailed {
      def apply(response: Response[IO]): IO[RemoteCheckFailed] =
        ResponseUtils
          .decodeBodyAsJson(response)
          .map { body =>
            RemoteCheckFailed(response.status, Some(body))
          }
          .handleErrorWith { error =>
            logger
              .error(error)(
                s"Remote check response could not be decoded as json, the status code was ${response.status}"
              )
              .as(RemoteCheckFailed(response.status, None))
          }
    }

    final case object RemoteCheckTimeout
        extends RemoteCheckError(s"Remote check took more than '${remoteCheckTimeout.toSeconds} seconds'", None)

    final case class RemoteUnknownHost(endpoint: Uri)
        extends RemoteCheckError(s"Remote check failed on '$endpoint' because host could not be resolved.", None)
  }

  final private class DeltaClientImpl(client: Client[IO], retryDelay: FiniteDuration) extends DeltaClient {

    override def projectStatistics(source: RemoteProjectSource): IO[ProjectStatistics] = {
      import org.http4s.circe.CirceEntityDecoder.*
      val remoteOrg     = source.project.organization.value
      val remoteProject = source.project.project.value
      val endpoint      = source.endpoint / "projects" / remoteOrg / remoteProject / "statistics"
      val request       = GET(endpoint, accept)
      client.expect[ProjectStatistics](request)
    }

    override def remaining(source: RemoteProjectSource, offset: Offset): IO[RemainingElems] = {
      import org.http4s.circe.CirceEntityDecoder.*
      SseClient.lastEventId(offset).flatMap { lastEventId =>
        val headers: Vector[Header.ToRaw] = Vector(accept, lastEventId)
        val request                       = GET(elemAddress(source) / "remaining", headers*)
        client.expect[RemainingElems](request)
      }
    }

    override def checkElems(source: RemoteProjectSource): IO[Unit] =
      client
        .expectOr[Unit](HEAD(elemAddress(source)))(RemoteCheckFailed(_))
        .timeoutTo(remoteCheckTimeout, IO.raiseError(RemoteCheckTimeout))
        .adaptError { case _: UnknownHostException => RemoteUnknownHost(source.endpoint) }

    override def elems(source: RemoteProjectSource, run: CompositeBranch.Run, offset: Offset): ElemStream[Unit] = {
      val suffix = run match {
        case CompositeBranch.Run.Main    => "continuous"
        case CompositeBranch.Run.Rebuild => "currents"
      }
      val uri    = elemAddress(source) / suffix

      SseClient(client, retryDelay, uri, offset).evalMapFilter { sse =>
        sse.data.filter(_.nonEmpty).traverseFilter { data =>
          decode[Elem[Unit]](data) match {
            case Right(elem) => IO.some(elem)
            case Left(err)   =>
              logger.error(err)(s"Failed to decode sse event '$sse'").as(None)
          }
        }
      }
    }

    private def tagParam(tag: Tag) = tag match {
      case Latest         => Map.empty[String, String]
      case UserTag(value) => Map("tag" -> value)
    }

    private def typeParam(restriction: IriFilter) = restriction match {
      case IriFilter.None           => Map.empty[String, List[String]]
      case IriFilter.Include(types) => Map("type" -> types.map(_.toString).toList)
    }

    private def elemAddress(source: RemoteProjectSource) = {
      val remoteOrg     = source.project.organization.value
      val remoteProject = source.project.project.value
      (source.endpoint / "elems" / remoteOrg / remoteProject)
        .withQueryParams(tagParam(source.selectFilter.tag))
        .withMultiValueQueryParams(typeParam(source.selectFilter.types))
    }

    override def resourceAsNQuads(source: RemoteProjectSource, id: Iri): IO[Option[NQuads]] = {
      val remoteOrg       = source.project.organization.value
      val remoteProject   = source.project.project.value
      val resourceUri     = source.endpoint / "resources" / remoteOrg / remoteProject / "_" / id.toString
      val resourceWithTag = source.resourceTag.fold(resourceUri) { tag => resourceUri.withQueryParam("tag", tag.value) }
      val request         = GET(resourceWithTag, Accept(RdfHttp4sMediaTypes.`application/n-quads`))
      client.expectOption[String](request).map(_.map { nq => NQuads(nq, id) })
    }
  }

  /**
    * Factory method for delta clients.
    */
  def apply(
      authTokenProvider: AuthTokenProvider,
      credentials: Credentials,
      retryDelay: FiniteDuration
  ): Resource[IO, DeltaClient] =
    EmberClientBuilder
      .default[IO]
      .withLogger(logger)
      .build
      .map { apply(_, authTokenProvider, credentials, retryDelay) }

  private[client] def apply(
      client: Client[IO],
      authTokenProvider: AuthTokenProvider,
      credentials: Credentials,
      retryDelay: FiniteDuration
  ): DeltaClient = {
    val authGzipClient = GZip()(TokenAuth(authTokenProvider, credentials)(client))
    new DeltaClientImpl(authGzipClient, retryDelay)
  }

}
