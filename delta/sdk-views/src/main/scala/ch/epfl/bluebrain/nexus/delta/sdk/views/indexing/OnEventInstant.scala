package ch.epfl.bluebrain.nexus.delta.sdk.views.indexing

import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import monix.bio.Task

import java.time.Instant

/**
  * Trait that gets called when there is an incoming ''ProjectScopedEvent''.
  */
trait OnEventInstant {

  /**
    * A [[Task]] to be executed in order to trigger the awake of an indexing stream when there is an incoming project
    * ''ProjectScopedEvent''.
    *
    * @param project
    *   the project of the event
    * @param prevEvent
    *   the previous event instant
    * @param currentEvent
    *   the current event instant
    */
  def awakeIndexingStream(project: ProjectRef, prevEvent: Option[Instant], currentEvent: Instant): Task[Unit]
}

object OnEventInstant {

  /**
    * Combine multiple [[OnEventInstant]] s into a single one
    */
  def combine(set: Set[OnEventInstant]): OnEventInstant =
    new OnEventInstant {
      override def awakeIndexingStream(
          project: ProjectRef,
          prevEvent: Option[Instant],
          currentEvent: Instant
      ): Task[Unit] =
        Task.parTraverse(set)(_.awakeIndexingStream(project, prevEvent, currentEvent)).void
    }
}
