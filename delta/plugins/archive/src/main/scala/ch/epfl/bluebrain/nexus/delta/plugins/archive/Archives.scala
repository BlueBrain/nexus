package ch.epfl.bluebrain.nexus.delta.plugins.archive

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveRejection.{ArchiveAlreadyExists, DuplicateResourcePath, PathIsNotAbsolute}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model.ArchiveState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.archive.model._
import monix.bio.{IO, UIO}

import java.nio.file.Path
import scala.annotation.unused

class Archives {}

object Archives {

  private[archive] def next(@unused state: ArchiveState, event: ArchiveCreated): ArchiveState =
    Current(
      id = event.id,
      project = event.project,
      resources = event.resources,
      createdAt = event.instant,
      createdBy = event.subject
    )

  private[archive] def eval(state: ArchiveState, command: CreateArchive)(implicit
      clock: Clock[UIO]
  ): IO[ArchiveRejection, ArchiveCreated] = {

    def checkPathDuplication: IO[ArchiveRejection, Unit] = {
      val duplicates = command.resources.toSortedSet
        .groupBy(_.path)
        .collect {
          case (path, refs) if refs.size > 1 => path
        }
        .toSet
      if (duplicates.nonEmpty) IO.raiseError(DuplicateResourcePath(duplicates))
      else IO.unit
    }

    def checkAbsolutePaths: IO[ArchiveRejection, Unit] = {
      val nonAbsolute = command.resources.foldLeft(Set.empty[Path]) { case (set, elem) =>
        if (!elem.path.isAbsolute) set + elem.path else set
      }
      if (nonAbsolute.nonEmpty) IO.raiseError(PathIsNotAbsolute(nonAbsolute))
      else IO.unit
    }

    state match {
      case Initial    =>
        for {
          _       <- checkPathDuplication
          _       <- checkAbsolutePaths
          instant <- IOUtils.instant
        } yield ArchiveCreated(command.id, command.project, command.resources, instant, command.subject)
      case _: Current =>
        IO.raiseError(ArchiveAlreadyExists(command.id, command.project))
    }
  }

}
