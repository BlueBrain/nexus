package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.{OrganizationScopedEvent, ProjectScopedEvent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.{JsonValue, SseEventLog}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream
import monix.bio.{IO, Task, UIO}
// $COVERAGE-OFF$

final class SseEventLogDummy(envelopes: Seq[Envelope[Event]], f: PartialFunction[Event, JsonValue.Aux[Event]])
    extends SseEventLog {
  override def stream(offset: Offset): Stream[Task, Envelope[JsonValue.Aux[Event]]]                         =
    DummyHelpers
      .eventsFromJournal[Event](envelopes, offset)
      .collect { case env if f.isDefinedAt(env.event) => env.map(f) }

  override def stream[R](
      org: Label,
      offset: Offset
  )(implicit mapper: Mapper[OrganizationRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]] =
    UIO.delay(
      DummyHelpers
        .eventsFromJournal[Event](envelopes, offset)
        .filter {
          case Envelope(ev: OrganizationScopedEvent, _, _, _, _, _) => org == ev.organizationLabel
          case _                                                    => false
        }
        .collect { case env if f.isDefinedAt(env.event) => env.map(f) }
    )

  override def stream[R](
      project: ProjectRef,
      offset: Offset
  )(implicit mapper: Mapper[ProjectRejection, R]): IO[R, Stream[Task, Envelope[JsonValue.Aux[Event]]]] =
    UIO.delay(
      DummyHelpers
        .eventsFromJournal[Event](envelopes, offset)
        .filter {
          case Envelope(ev: ProjectScopedEvent, _, _, _, _, _) => project == ev.project
          case _                                               => false
        }
        .map(_.map(f))
    )
}
// $COVERAGE-ON$
