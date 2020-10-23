package ch.epfl.bluebrain.nexus.cli.dummies

import java.util.UUID

import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.clients.{EventStreamClient, ProjectClient}
import ch.epfl.bluebrain.nexus.cli.sse._
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, LabeledEvent}
import fs2.{Pipe, Stream}

class TestEventStreamClient[F[_]](events: List[Event], projectClient: ProjectClient[F])(implicit F: Sync[F])
    extends EventStreamClient[F] {

  private val noOffset: Offset                   = Offset(new UUID(0L, 0L))
  private val offsetEvents: Seq[(Offset, Event)] = events.map { ev =>
    (Offset(new UUID(ev.instant.toEpochMilli, 0L)), ev)
  }

  private def saveOffset(lastEventIdCache: Ref[F, Option[Offset]]): Pipe[F, (Offset, Event), Event] =
    _.evalMap { case (offset, event) => lastEventIdCache.update(_ => Some(offset)) >> F.pure(event) }

  private def eventsFrom(lastEventIdCache: Ref[F, Option[Offset]]): F[Seq[(Offset, Event)]]         =
    lastEventIdCache.get.map(lastEventId =>
      offsetEvents.dropWhile { case (offset, _) =>
        offset.value.getMostSignificantBits <= lastEventId.getOrElse(noOffset).value.getMostSignificantBits
      }
    )

  private def eventAndLabels(event: Event): F[ClientErrOr[LabeledEvent]] =
    projectClient.labels(event.organization, event.project).map(_.map { case (org, proj) => (event, org, proj) })

  override def apply(lastEventId: Option[Offset]): F[EventStream[F]]     =
    Ref.of(lastEventId).flatMap { ref =>
      val stream = eventsFrom(ref).map { events =>
        Stream.fromIterator[F](events.iterator).through(saveOffset(ref)).evalMap(eventAndLabels)
      }
      F.delay(EventStream(stream, ref))
    }

  override def apply(organization: OrgLabel, lastEventId: Option[Offset]): F[EventStream[F]] =
    Ref.of(lastEventId).flatMap { ref =>
      val stream = eventsFrom(ref).map { events =>
        Stream.fromIterator[F](events.iterator).through(saveOffset(ref)).evalMap(eventAndLabels).filter {
          case Right((_, org, _)) => org == organization
          case Left(_)            => true
        }
      }
      F.delay(EventStream(stream, ref))
    }

  override def apply(organization: OrgLabel, project: ProjectLabel, lastEventId: Option[Offset]): F[EventStream[F]] =
    Ref.of(lastEventId).flatMap { ref =>
      val stream = eventsFrom(ref).map { events =>
        Stream.fromIterator[F](events.iterator).through(saveOffset(ref)).evalMap(eventAndLabels).filter {
          case Right((_, org, proj)) => org == organization && proj == project
          case Left(_)               => true
        }
      }
      F.delay(EventStream(stream, ref))
    }
}
