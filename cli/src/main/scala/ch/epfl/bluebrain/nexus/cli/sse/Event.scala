package ch.epfl.bluebrain.nexus.cli.sse

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.cli.utils.Codecs
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Json}
import org.http4s.Uri

/**
  * The Nexus KG event type.
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
    eventType: EventType,
    resourceId: Uri,
    rev: Long,
    organization: OrgUuid,
    project: ProjectUuid,
    resourceTypes: Set[Uri],
    instant: Instant,
    raw: Json
)

object Event extends Codecs {

  final private[Event] case class APIEvent(
      `_organizationUuid`: UUID,
      `_projectUuid`: UUID,
      `@type`: EventType,
      `_types`: Option[Set[Uri]],
      `_resourceId`: Uri,
      `_rev`: Option[Long],
      `_instant`: Instant
  ) {
    def asEvent(raw: Json): Event =
      Event(
        `@type`,
        `_resourceId`,
        `_rev`.getOrElse(1L),
        OrgUuid(`_organizationUuid`),
        ProjectUuid(`_projectUuid`),
        `_types`.getOrElse(Set.empty[Uri]),
        `_instant`,
        raw
      )
  }

  private[Event] object APIEvent {
    implicit val apiEventDecoder: Decoder[APIEvent] = deriveDecoder[APIEvent]
  }

  implicit final val eventDecoder: Decoder[Event] =
    Decoder.instance { cursor => cursor.as[APIEvent].map(_.asEvent(cursor.value)) }

}
