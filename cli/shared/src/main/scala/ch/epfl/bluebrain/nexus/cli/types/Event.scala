package ch.epfl.bluebrain.nexus.cli.types

import java.time.Instant

import org.http4s.Uri

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
