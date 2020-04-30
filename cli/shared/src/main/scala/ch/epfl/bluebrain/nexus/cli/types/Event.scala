package ch.epfl.bluebrain.nexus.cli.types

import java.time.Instant
import java.util.UUID

import cats.Applicative
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, ProjectClient}
import ch.epfl.bluebrain.nexus.cli.error.ClientError.SerializationError
import io.circe.{Decoder, DecodingFailure, Json}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import org.http4s.{ServerSentEvent, Uri}

/**
  * An event
  *
  * @param eventType     the event type
  * @param resourceId    the @id Uri of the resource
  * @param rev           the resource revision that this event generated
  * @param organization  the organization label of the event
  * @param project       the project label of the event
  * @param resourceTypes the @type Set of Uris of the resource
  * @param instant       the instant when the event was recorded
  * @param raw           the raw json representation of the event
  */
final case class Event(
    eventType: String,
    resourceId: Uri,
    rev: Long,
    organization: Label,
    project: Label,
    resourceTypes: Set[Uri],
    instant: Instant,
    raw: Json
)

object Event {
  final private[Event] case class NexusAPIEvent(
      `_organizationUuid`: UUID,
      `_projectUuid`: UUID,
      `@type`: String,
      `_types`: Set[Uri],
      `_resourceId`: Uri,
      `_rev`: Option[Long],
      `_instant`: Instant
  )

  object NexusAPIEvent {
    implicit private[types] val uriDecoder: Decoder[Uri] =
      Decoder.decodeString.emap(str => Uri.fromString(str).leftMap(_ => "Failed to decode str as Uri"))
    implicit private[Event] val nexusAPIEventDecoder: Decoder[NexusAPIEvent] = deriveDecoder[NexusAPIEvent]
  }

  final def apply[F[_]](value: ServerSentEvent, projectClient: ProjectClient[F])(
      implicit F: Applicative[F]
  ): F[ClientErrOr[Event]] = {
    decode[Json](value.data).flatMap { raw => decode[NexusAPIEvent](value.data).map(ev => (ev, raw)) } match {
      case Left(err) => F.pure(Left(SerializationError(err.getMessage, "NexusAPIEvent", Some(value.data))))
      case Right((NexusAPIEvent(orgUuid, projectUuid, tpe, types, resourceId, revOpt, instant), raw)) =>
        projectClient
          .label(orgUuid, projectUuid)
          .map(_.map {
            case (org, project) => Event(tpe, resourceId, revOpt.getOrElse(1L), org, project, types, instant, raw)
          })
    }
  }

  final def eventDecoder(orgResolver: UUID => Option[Label], projResolver: UUID => Option[Label]): Decoder[Event] =
    Decoder.instance { cursor =>
      for {
        raw   <- cursor.as[Json]
        apiEv <- cursor.as[NexusAPIEvent]

        NexusAPIEvent(orgUuid, projectUuid, tpe, types, resourceId, revOpt, instant) = apiEv

        org <- orgResolver(orgUuid).toRight(
                DecodingFailure(
                  s"Unable to convert org UUID '$orgUuid' to label",
                  cursor.downField("_organizationUuid").history
                )
              )
        project <- projResolver(projectUuid).toRight(
                    DecodingFailure(
                      s"Unable to convert project UUID '$orgUuid' to label",
                      cursor.downField("_projectUuid").history
                    )
                  )
      } yield Event(tpe, resourceId, revOpt.getOrElse(1L), org, project, types, instant, raw)
    }
}
