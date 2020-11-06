package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{Offset, Sequence}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Permissions implementation that uses a synchronized in memory journal.
  *
  * @param minimum   the minimum set of permissions
  * @param journal   a ref to the journal
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class PermissionsDummy private (
    override val minimum: Set[Permission],
    journal: IORef[Vector[Envelope[PermissionsEvent]]],
    semaphore: IOSemaphore,
    maxStreamSize: Long
)(implicit clock: Clock[UIO])
    extends Permissions {

  override def fetch: UIO[PermissionsResource] =
    currentState.map(_.toResource(minimum))

  override def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource] =
    stateAt(rev).map(_.toResource(minimum))

  override def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller))

  override def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller))

  override def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller))

  override def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller))

  override def events(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    DummyHelpers.eventsFromJournal(journal.get, offset, maxStreamSize)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[PermissionsEvent]] =
    DummyHelpers.currentEventsFromJournal(journal.get, offset, maxStreamSize)

  private def currentState: UIO[PermissionsState] =
    journal.get.map { envelopes =>
      envelopes.foldLeft[PermissionsState](Initial)((state, envelope) =>
        Permissions.next(minimum)(state, envelope.event)
      )
    }

  private def stateAt(rev: Long): IO[RevisionNotFound, PermissionsState] =
    journal.get.flatMap { envelopes =>
      val state = envelopes.foldLeft[PermissionsState](Initial) {
        case (state, envelope) if envelope.event.rev <= rev => Permissions.next(minimum)(state, envelope.event)
        case (state, _)                                     => state
      }
      if (state.rev == rev) UIO.pure(state)
      else IO.raiseError(RevisionNotFound(rev, envelopes.size.toLong))
    }

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    semaphore.withPermit {
      for {
        envelopes <- journal.get
        current    = envelopes.foldLeft[PermissionsState](Initial)((state, envelope) =>
                       Permissions.next(minimum)(state, envelope.event)
                     )
        event     <- Permissions.evaluate(minimum)(current, cmd)
        _         <- journal.set(
                       envelopes :+ Envelope(
                         event,
                         ClassUtils.simpleName(event),
                         Sequence((envelopes.size + 1).toLong),
                         persistenceId,
                         (envelopes.size + 1).toLong,
                         event.instant.toEpochMilli
                       )
                     )
      } yield Permissions.next(minimum)(current, event).toResource(minimum)
    }
}

object PermissionsDummy {

  /**
    * Permissions resource id.
    */
  val id: Iri = iri"http://localhost/v1/permissions"

  /**
    * Creates a new dummy Permissions implementation.
    *
    * @param minimum       the minimum set of permissions
    * @param maxStreamSize truncate event stream after this size
    */
  final def apply(
      minimum: Set[Permission],
      maxStreamSize: Long = Long.MaxValue
  )(implicit clock: Clock[UIO]): UIO[PermissionsDummy] =
    for {
      ref <- IORef.of(Vector.empty[Envelope[PermissionsEvent]])
      sem <- IOSemaphore(1L)
    } yield new PermissionsDummy(minimum, ref, sem, maxStreamSize)
}
