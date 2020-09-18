package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.PermissionsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import monix.bio.{IO, UIO}
import org.apache.jena.iri.IRI

/**
  * A dummy Permissions implementation that uses a synchronized in memory journal.
  *
  * @param minimum   the minimum set of permissions
  * @param journal   a ref to the journal
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class PermissionsDummy private (
    override val minimum: Set[Permission],
    journal: IORef[Vector[PermissionsEvent]],
    semaphore: IOSemaphore
) extends Permissions {

  /**
    * @return the permissions singleton persistence id
    */
  override val persistenceId: String = "permissions"

  /**
    * @return the current permissions as a resource
    */
  override def fetch: UIO[PermissionsResource] =
    currentState.map(_.toResource(id, minimum))

  /**
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  override def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource] =
    stateAt(rev).map(_.toResource(id, minimum))

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  override def replace(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(ReplacePermissions(rev, permissions, caller))

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  override def append(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(AppendPermissions(rev, permissions, caller))

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @param caller      a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  override def subtract(
      permissions: Set[Permission],
      rev: Long
  )(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(SubtractPermissions(rev, permissions, caller))

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev    the last known revision of the resource
    * @param caller a reference to the subject that initiated the action
    * @return the new resource or a description of why the change was rejected
    */
  override def delete(rev: Long)(implicit caller: Subject): IO[PermissionsRejection, PermissionsResource] =
    eval(DeletePermissions(rev, caller))

  private def currentState: UIO[PermissionsState] =
    journal.get.map { events =>
      events.foldLeft[PermissionsState](Initial)(Permissions.next(minimum))
    }

  private def stateAt(rev: Long): IO[RevisionNotFound, PermissionsState] =
    journal.get.flatMap { events =>
      if (events.size < rev) IO.raiseError(RevisionNotFound(rev, events.size.toLong))
      else {
        UIO.pure(
          events
            .foldLeft[PermissionsState](Initial) {
              case (state, event) if event.rev <= rev => Permissions.next(minimum)(state, event)
              case (state, _)                         => state
            }
        )
      }
    }

  private def eval(cmd: PermissionsCommand): IO[PermissionsRejection, PermissionsResource] =
    semaphore.withPermit {
      for {
        events <- journal.get
        current = events.foldLeft[PermissionsState](Initial)(Permissions.next(minimum))
        event  <- Permissions.evaluate(minimum)(current, cmd)
        _      <- journal.set(events :+ event)
      } yield Permissions.next(minimum)(current, event).toResource(id, minimum)
    }
}

object PermissionsDummy {

  /**
    * Permissions resource id.
    */
  val id: IRI = iri"http://localhost/v1/permissions"

  /**
    * Creates a new dummy Permissions implementation.
    *
    * @param minimum the minimum set of permissions
    */
  final def apply(minimum: Set[Permission]): UIO[PermissionsDummy] =
    for {
      ref <- IORef.of(Vector.empty[PermissionsEvent])
      sem <- IOSemaphore(1L)
    } yield new PermissionsDummy(minimum, ref, sem)
}
