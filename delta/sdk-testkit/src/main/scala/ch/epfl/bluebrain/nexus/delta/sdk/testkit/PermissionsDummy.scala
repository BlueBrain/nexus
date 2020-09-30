package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Sequence
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import fs2.Stream
import fs2.concurrent.Queue
import monix.bio.{IO, Task, UIO}

import scala.concurrent.duration.DurationInt

/**
  * A dummy Permissions implementation that uses a synchronized in memory journal.
  *
  * @param minimum   the minimum set of permissions
  * @param journal   a ref to the journal
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class PermissionsDummy private (
    override val minimum: Set[Permission],
    journal: IORef[Vector[Envelope[PermissionsEvent, Sequence]]],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO])
    extends Permissions[Sequence] {

  override val persistenceId: String = "permissions-permissions"

  override def fetch: UIO[PermissionsResource] =
    currentState.map(_.toResource(id, minimum))

  override def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource] =
    stateAt(rev).map(_.toResource(id, minimum))

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

  override def events(offset: Option[Sequence]): Stream[Task, Envelope[PermissionsEvent, Sequence]] = {
    def addNotSeen(queue: Queue[Task, Envelope[PermissionsEvent, Sequence]], seenCount: Int): Task[Unit] = {
      journal.get.flatMap { eventVector =>
        val delta = eventVector.drop(seenCount)
        if (delta.isEmpty) Task.sleep(10.milliseconds) >> addNotSeen(queue, seenCount)
        else queue.offer1(delta.head) >> addNotSeen(queue, seenCount + 1)
      }
    }

    val streamF = for {
      queue <- Queue.unbounded[Task, Envelope[PermissionsEvent, Sequence]]
      fiber <- addNotSeen(queue, 0).start
      stream = queue.dequeue.onFinalize(fiber.cancel)
    } yield stream

    val stream = Stream.eval(streamF).flatten
    offset match {
      case Some(value) => stream.dropWhile(_.offset <= value)
      case None        => stream
    }
  }

  override def currentEvents(offset: Option[Sequence]): Stream[Task, Envelope[PermissionsEvent, Sequence]] = {
    val stream = Stream.eval(journal.get).flatMap(es => Stream.emits(es))
    offset match {
      case Some(value) => stream.dropWhile(_.offset <= value)
      case None        => stream
    }
  }

  private def currentState: UIO[PermissionsState] =
    journal.get.map { events =>
      events.foldLeft[PermissionsState](Initial)((state, event) => Permissions.next(minimum)(state, event.event))
    }

  private def stateAt(rev: Long): IO[RevisionNotFound, PermissionsState] =
    journal.get.flatMap { events =>
      val state = events.foldLeft[PermissionsState](Initial) {
        case (state, event) if event.event.rev <= rev => Permissions.next(minimum)(state, event.event)
        case (state, _)                               => state
      }
      if (state.rev == rev) UIO.pure(state)
      else IO.raiseError(RevisionNotFound(rev, events.size.toLong))
    }

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    semaphore.withPermit {
      for {
        events <- journal.get
        current =
          events.foldLeft[PermissionsState](Initial)((state, event) => Permissions.next(minimum)(state, event.event))
        event  <- Permissions.evaluate(minimum)(current, cmd)
        _      <- journal.set(
                    events :+ Envelope(
                      event,
                      Sequence((events.size + 1).toLong),
                      persistenceId,
                      (events.size + 1).toLong,
                      event.instant.toEpochMilli
                    )
                  )
      } yield Permissions.next(minimum)(current, event).toResource(id, minimum)
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
    * @param minimum the minimum set of permissions
    */
  final def apply(minimum: Set[Permission])(implicit clock: Clock[UIO]): UIO[PermissionsDummy] =
    for {
      ref <- IORef.of(Vector.empty[Envelope[PermissionsEvent, Sequence]])
      sem <- IOSemaphore(1L)
    } yield new PermissionsDummy(minimum, ref, sem)
}
