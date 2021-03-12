package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{IOUtils, UUIDF}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model._
import monix.bio.{IO, UIO}

object CompositeViews {

  type ValidateProjection = CompositeViewProjection => IO[CompositeViewProjectionRejection, Unit]
  type ValidateSource     = CompositeViewSource => IO[CompositeViewSourceRejection, Unit]

  private[compositeviews] def next(
      state: CompositeViewState,
      event: CompositeViewEvent
  ): CompositeViewState = {
    // format: off
    def created(e: CompositeViewCreated): CompositeViewState = state match {
      case Initial     => Current(e.id, e.project, e.uuid, e.value, e.source, Map.empty, e.rev, deprecated = false,  e.instant, e.subject, e.instant, e.subject)
      case s: Current  => s
    }

    def updated(e: CompositeViewUpdated): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, value = e.value, source = e.source, updatedAt = e.instant, updatedBy = e.subject)
    }

    def tagAdded(e: CompositeViewTagAdded): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, tags = s.tags + (e.tag -> e.targetRev), updatedAt = e.instant, updatedBy = e.subject)
    }
    // format: on

    def deprecated(e: CompositeViewDeprecated): CompositeViewState = state match {
      case Initial    => Initial
      case s: Current => s.copy(rev = e.rev, deprecated = true, updatedAt = e.instant, updatedBy = e.subject)
    }

    event match {
      case e: CompositeViewCreated    => created(e)
      case e: CompositeViewUpdated    => updated(e)
      case e: CompositeViewTagAdded   => tagAdded(e)
      case e: CompositeViewDeprecated => deprecated(e)
    }
  }

  private[compositeviews] def evaluate(
      validateSource: ValidateSource,
      validateProjection: ValidateProjection,
      maxSources: Int,
      maxProjections: Int
  )(
      state: CompositeViewState,
      cmd: CompositeViewCommand
  )(implicit
      clock: Clock[UIO],
      uuidF: UUIDF
  ): IO[CompositeViewRejection, CompositeViewEvent] = {

    def validate(value: CompositeViewValue): IO[CompositeViewRejection, Unit] = for {
      _ <- IO.raiseWhen(value.sources.value.size > maxSources)(TooManySources(value.sources.value.size, maxSources))
      _ <- IO.raiseWhen(value.projections.value.size > maxProjections)(
             TooManyProjections(value.projections.value.size, maxProjections)
           )
      _ <- IO.parTraverseUnordered(value.sources.value)(validateSource).void
      _ <- IO.parTraverseUnordered(value.projections.value)(validateProjection).void
    } yield ()

    def create(c: CreateCompositeView) = state match {
      case Initial =>
        for {
          t <- IOUtils.instant
          u <- uuidF()
          _ <- validate(c.value)
        } yield CompositeViewCreated(c.id, c.project, u, c.value, c.source, 1L, t, c.subject)
      case _       => IO.raiseError(ViewAlreadyExists(c.id, c.project))
    }

    def update(c: UpdateCompositeView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        for {
          _ <- validate(c.value)
          t <- IOUtils.instant
        } yield CompositeViewUpdated(c.id, c.project, s.uuid, c.value, c.source, s.rev + 1L, t, c.subject)
    }

    def tag(c: TagCompositeView) = state match {
      case Initial                                                =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev                           =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated                             =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current if c.targetRev <= 0L || c.targetRev > s.rev =>
        IO.raiseError(RevisionNotFound(c.targetRev, s.rev))
      case s: Current                                             =>
        IOUtils.instant.map(
          CompositeViewTagAdded(c.id, c.project, s.uuid, c.targetRev, c.tag, s.rev + 1L, _, c.subject)
        )
    }

    def deprecate(c: DeprecateCompositeView) = state match {
      case Initial                      =>
        IO.raiseError(ViewNotFound(c.id, c.project))
      case s: Current if s.rev != c.rev =>
        IO.raiseError(IncorrectRev(c.rev, s.rev))
      case s: Current if s.deprecated   =>
        IO.raiseError(ViewIsDeprecated(c.id))
      case s: Current                   =>
        IOUtils.instant.map(CompositeViewDeprecated(c.id, c.project, s.uuid, s.rev + 1L, _, c.subject))
    }

    cmd match {
      case c: CreateCompositeView    => create(c)
      case c: UpdateCompositeView    => update(c)
      case c: TagCompositeView       => tag(c)
      case c: DeprecateCompositeView => deprecate(c)
    }

  }
}
