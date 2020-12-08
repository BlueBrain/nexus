package ch.epfl.bluebrain.nexus.delta.plugins.file

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.Digest.NotComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.FileState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.file.model.{FileCommand, FileEvent, FileRejection, FileState}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils
import monix.bio.{IO, UIO}

class Files {}

object Files {

  /**
    * The files module type.
    */
  final val moduleType: String = "file"

  private[file] def next(
      state: FileState,
      event: FileEvent
  ): FileState = {
    // format: off
    def created(e: FileCreated): FileState = state match {
      case Initial     => Current(e.id, e.project, e.storage, e.attributes, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: FileUpdated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, storage = e.storage, attributes = e.attributes, updatedAt = e.instant, updatedBy = e.subject)
    }

    def updatedAttributes(e: FileComputedAttributesUpdated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, storage = e.storage, attributes = s.attributes.copy( mediaType = e.mediaType, bytes = e.bytes, digest = e.digest), updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: FileTagAdded): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: FileDeprecated): FileState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: FileCreated                   => created(e)
      case e: FileUpdated                   => updated(e)
      case e: FileComputedAttributesUpdated => updatedAttributes(e)
      case e: FileTagAdded                  => tagAdded(e)
      case e: FileDeprecated                => deprecated(e)
    }
  }

  private[file] def evaluate(
      state: FileState,
      cmd: FileCommand
  )(implicit clock: Clock[UIO]): IO[FileRejection, FileEvent] = {

    def create(c: CreateFile) = state match {
      case Initial =>
        IOUtils.instant.map(FileCreated(c.id, c.project, c.storage, c.attributes, 1L, _, c.subject))
      case _       =>
        IO.raiseError(FileAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateFile) = state match {
      case Initial                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current if s.attributes.digest == NotComputedDigest => IO.raiseError(DigestNotComputed(c.id))
      case s: Current                                             =>
        IOUtils.instant.map(FileUpdated(c.id, c.project, c.storage, c.attributes, s.rev + 1L, _, c.subject))
    }

    def updateAttributes(c: UpdateFileComputedAttributes) = state match {
      case Initial                      => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current                   =>
        // format: off
        IOUtils.instant
          .map(FileComputedAttributesUpdated(c.id, c.project, c.storage, c.mediaType, c.bytes, c.digest, s.rev + 1L, _, c.subject))
      // format: on
    }

    def tag(c: TagFile) = state match {
      case Initial                                                => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev => IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(FileTagAdded(c.id, c.project, c.targetRev, c.tag, s.rev + 1L, _, c.subject))
    }

    def deprecate(c: DeprecateFile) = state match {
      case Initial                      => IO.raiseError(FileNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev => IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   => IO.raiseError(FileIsDeprecated(c.id))
      case s: Current                   => IOUtils.instant.map(FileDeprecated(c.id, c.project, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateFile                   => create(c)
      case c: UpdateFile                   => update(c)
      case c: UpdateFileComputedAttributes => updateAttributes(c)
      case c: TagFile                      => tag(c)
      case c: DeprecateFile                => deprecate(c)
    }
  }

}
