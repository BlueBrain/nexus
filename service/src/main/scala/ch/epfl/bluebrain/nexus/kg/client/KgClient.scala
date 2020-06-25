package ch.epfl.bluebrain.nexus.kg.client

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.{Accept, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpEntity, HttpMessage, HttpRequest, StatusCodes}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.persistence.query.{EventEnvelope, Offset, Sequence, TimeBasedUUID}
import akka.stream.scaladsl.Source
import cats.effect.{ContextShift, Effect, IO, LiftIO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.client.IamClientError.{Forbidden, Unauthorized}
import ch.epfl.bluebrain.nexus.kg.client.KgClientError._
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Event, ResourceF, ResourceV}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, DecodingFailure, ParsingFailure}

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.control.NonFatal

class KgClient[F[_]] private[client] (
    config: KgClientConfig,
    source: EventSource[Event],
    resourceClientFromRef: ProjectRef => HttpClient[F, ResourceV]
)(implicit
    F: Effect[F]
) {

  private val accept = Accept(`application/json`.mediaType, `application/ld+json`)

  /**
    * Fetch a resource from the passed ''project'' with the passed ''id''.
    *
    * @return Some(resource) if found and None otherwise, wrapped in an effect type ''F[_]''
    */
  def resource(project: Project, id: AbsoluteIri)(implicit
      credentials: Option[AccessToken]
  ): F[Option[ResourceV]] = {
    val endpoint = config.resourcesIri + (project.organizationLabel / project.label / "_" / id.asString)
    resourceClientFromRef(project.ref)(requestFrom(endpoint, Query("format" -> "expanded")))
      .map[Option[ResourceV]](Some(_))
      .recoverWith { case NotFound(_) => F.pure(None) }
  }

  /**
    * Fetch a resource from the passed ''project'' with the passed ''id'' and ''tag''.
    *
    * @return Some(resource) if found and None otherwise, wrapped in an effect type ''F[_]''
    */
  def resource(project: Project, id: AbsoluteIri, tag: String)(implicit
      credentials: Option[AccessToken]
  ): F[Option[ResourceV]] = {
    val endpoint = config.resourcesIri + (project.organizationLabel / project.label / "_" / id.asString)
    resourceClientFromRef(project.ref)(requestFrom(endpoint, Query("format" -> "expanded", "tag" -> tag)))
      .map[Option[ResourceV]](Some(_))
      .recoverWith { case NotFound(_) => F.pure(None) }
  }

  /**
    * Streams the events for the passed ''project''.
    *
    * @return a source of [[EventEnvelope]]
    */
  def events(project: ProjectLabel, offset: Offset)(implicit
      credentials: Option[AccessToken]
  ): Source[EventEnvelope, NotUsed] =
    source(config.resourcesIri + (project.organization / project.value / "events"), toString(offset)).map {
      case (off, event) => EventEnvelope(off, event.id.value.asString, event.rev, event, event.rev)
    }

  private def toString(offset: Offset): Option[String] =
    offset match {
      case Sequence(value)      => Some(value.toString)
      case TimeBasedUUID(value) => Some(value.toString)
      case _                    => None
    }

  private def requestFrom(iri: AbsoluteIri, query: Query)(implicit credentials: Option[AccessToken]) = {
    val request = Get(iri.asAkka.withQuery(query)).addHeader(accept)
    credentials.map(token => request.addCredentials(OAuth2BearerToken(token.value))).getOrElse(request)
  }

}

object KgClient {

  private def httpClient[F[_], A: ClassTag](implicit
      L: LiftIO[F],
      F: Effect[F],
      as: ActorSystem,
      ec: ExecutionContext,
      cl: UntypedHttpClient[F],
      um: FromEntityUnmarshaller[A]
  ): HttpClient[F, A] =
    new HttpClient[F, A] {
      private val logger                                  = Logger(s"AdminHttpClient[${implicitly[ClassTag[A]]}]")
      implicit private val contextShift: ContextShift[IO] = IO.contextShift(ec)

      private def handleError[B](req: HttpRequest): Throwable => F[B] = {
        case NonFatal(th) =>
          logger.error(s"Unexpected response for KG call. Request: '${req.method} ${req.uri}'", th)
          F.raiseError(UnknownError(StatusCodes.InternalServerError, th.getMessage))
      }

      override def apply(req: HttpRequest): F[A] =
        cl(req).handleErrorWith(handleError(req)).flatMap { resp =>
          resp.status match {
            case StatusCodes.NotFound       =>
              cl.toString(resp.entity).flatMap { entityAsString =>
                F.raiseError[A](NotFound(entityAsString))
              }
            case StatusCodes.Unauthorized   =>
              cl.toString(resp.entity).flatMap { entityAsString =>
                F.raiseError[A](Unauthorized(entityAsString))
              }
            case StatusCodes.Forbidden      =>
              logger.error(s"Received Forbidden when accessing '${req.method.name()} ${req.uri.toString()}'.")
              cl.toString(resp.entity).flatMap { entityAsString =>
                F.raiseError[A](Forbidden(entityAsString))
              }
            case other if other.isSuccess() =>
              val value = L.liftIO(IO.fromFuture(IO(um(resp.entity))))
              value.recoverWith {
                case pf: ParsingFailure  =>
                  logger
                    .error(s"Failed to parse a successful response of '${req.method.name()} ${req.getUri().toString}'.")
                  F.raiseError[A](UnmarshallingError(pf.getMessage()))
                case df: DecodingFailure =>
                  logger
                    .error(
                      s"Failed to decode a successful response of '${req.method.name()} ${req.getUri().toString}'."
                    )
                  F.raiseError(UnmarshallingError(df.getMessage()))
              }
            case other                      =>
              cl.toString(resp.entity).flatMap { entityAsString =>
                logger.error(
                  s"Received '${other.value}' when accessing '${req.method.name()} ${req.uri.toString()}', response entity as string: '$entityAsString.'"
                )
                F.raiseError[A](UnknownError(other, entityAsString))
              }
          }
        }

      override def discardBytes(entity: HttpEntity): F[HttpMessage.DiscardedEntity] =
        cl.discardBytes(entity)

      override def toString(entity: HttpEntity): F[String] =
        cl.toString(entity)
    }

  /**
    * Construct [[KgClient]].
    *
    * @param cfg configuration for the client
    */
  def apply[F[_]: Effect](
      cfg: KgClientConfig
  )(implicit as: ActorSystem): KgClient[F] = {
    implicit val ec: ExecutionContext              = as.dispatcher
    implicit val ucl: UntypedHttpClient[F]         = HttpClient.untyped[F]
    val rc: ProjectRef => HttpClient[F, ResourceV] = (ref: ProjectRef) => {
      implicit val resourceVDecoder: Decoder[ResourceV] = ResourceF.resourceVGraphDecoder(ref)
      httpClient[F, ResourceV]
    }
    val sse: EventSource[Event]                    = EventSource[Event](cfg)
    new KgClient(cfg, sse, rc)
  }
}
