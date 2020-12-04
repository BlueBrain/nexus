package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageCommand.{CreateStorage, DeprecateStorage, TagStorage, UpdateStorage}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageEvent.{StorageCreated, StorageDeprecated, StorageTagAdded, StorageUpdated}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.{StorageCommand, StorageEvent, StorageRejection, StorageState, StorageValue}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import monix.bio.{IO, UIO}

class Storages {}

object Storages {

  /**
    * The storages module type.
    */
  final val moduleType: String = "storage"

  private[storage] type StorageAccess = StorageValue => IO[StorageNotAccessible, Unit]

  private[storage] def next(
      state: StorageState,
      event: StorageEvent
  ): StorageState = {
    // format: off
    def created(e: StorageCreated): StorageState = state match {
      case Initial     => Current(e.id, e.project, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: StorageUpdated): StorageState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: StorageTagAdded): StorageState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: StorageDeprecated): StorageState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: StorageCreated    => created(e)
      case e: StorageUpdated    => updated(e)
      case e: StorageTagAdded   => tagAdded(e)
      case e: StorageDeprecated => deprecated(e)
    }
  }

  private[storage] def evaluate(
      state: StorageState,
      cmd: StorageCommand
  )(implicit clock: Clock[UIO]): IO[StorageRejection, StorageEvent] =
    evaluate(StorageAccess.apply)(state, cmd)

  private[storage] def evaluate(access: StorageAccess)(
      state: StorageState,
      cmd: StorageCommand
  )(implicit clock: Clock[UIO]): IO[StorageRejection, StorageEvent] = {

    def create(c: CreateStorage) = state match {
      case Initial =>
        access(c.value) >> IOUtils.instant.map(StorageCreated(c.id, c.project, c.value, c.source, 1L, _, c.subject))
      case _       =>
        IO.raiseError(StorageAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateStorage) = state match {
      case Initial                                  => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev             => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated               => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current if c.value.tpe != s.value.tpe =>
        IO.raiseError(DifferentStorageType(s.id, c.value.tpe, s.value.tpe))
      case s: Current                               =>
        access(c.value) >>
          IOUtils.instant.map(StorageUpdated(c.id, c.project, c.value, c.source, s.rev + 1L, _, c.subject))
    }

    def tag(c: TagStorage) = state match {
      case Initial                                                => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(StorageTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1L, _, c.subject))
    }

    def deprecate(c: DeprecateStorage) = state match {
      case Initial                      => IO.raiseError(StorageNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(StorageIsDeprecated(c.id))
      case s: Current                   => IOUtils.instant.map(StorageDeprecated(c.id, c.project, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateStorage    => create(c)
      case c: UpdateStorage    => update(c)
      case c: TagStorage       => tag(c)
      case c: DeprecateStorage => deprecate(c)
    }
  }

}
