package ch.epfl.bluebrain.nexus.cli.types

import java.time.Instant
import java.util.UUID

import cats.Applicative
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, ProjectClient}
import ch.epfl.bluebrain.nexus.cli.error.ClientError.SerializationError
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.parser.decode
import org.http4s.{ServerSentEvent, Uri}

/**
  * An event
  *
  * @param eventType     the event type
  * @param resourceId    the @id Uri of the resource
  * @param organization  the organization label of the event
  * @param project       the project label of the event
  * @param resourceTypes the @type Set of Uris of the resource
  * @param instant       the instant when the event was recorded
  */
final case class Event(
    eventType: String,
    resourceId: Uri,
    organization: Label,
    project: Label,
    resourceTypes: Set[Uri],
    instant: Instant
)

object Event {
  private[Event] final case class NexusAPIEvent(
      `_organizationUuid`: UUID,
      `_projectUuid`: UUID,
      `@type`: String,
      `_types`: Set[Uri],
      `_resourceId`: Uri,
      `_instant`: Instant
  )

  object NexusAPIEvent {
    private[types] implicit val uriDecoder: Decoder[Uri] =
      Decoder.decodeString.emap(str => Uri.fromString(str).leftMap(_ => "Failed to decode str as Uri"))
    private[Event] implicit val nexusAPIEventDecoder: Decoder[NexusAPIEvent] = deriveDecoder[NexusAPIEvent]
  }

  final def apply[F[_]](value: ServerSentEvent, projectClient: ProjectClient[F])(
      implicit F: Applicative[F]
  ): F[ClientErrOr[Event]] =
    decode[NexusAPIEvent](value.data) match {
      case Left(err) => F.pure(Left(SerializationError(err.getMessage, "NexusAPIEvent", Some(value.data))))
      case Right(NexusAPIEvent(orgUuid, projectUuid, tpe, types, resourceId, instant)) =>
        projectClient
          .label(orgUuid, projectUuid)
          .map(_.map { case (org, project) => Event(tpe, resourceId, org, project, types, instant) })
    }
}
