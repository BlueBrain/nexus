package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.Clock
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.FullRebuild
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.{CompositeProgressStore, CompositeRestartStore}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}

/**
  * Handles projection operation for composite views
  */
trait CompositeProjections {

  /**
    * Return the composite progress for the given view
    * @param view
    *   the reference to the composite view
    * @param rev
    *   the revision of the view
    */
  def progress(view: ViewRef, rev: Int): UIO[CompositeProgress]

  /**
    * Creates the save [[Operation]] for a given branch of the specified view.
    *
    * Saves both the progress and the errors
    *
    * @param branch
    *   the composite branch
    * @param progress
    *   the offset to save
    */
  def saveOperation(
      metadata: ProjectionMetadata,
      view: ViewRef,
      rev: Int,
      branch: CompositeBranch,
      progress: ProjectionProgress
  ): Operation

  /**
    * Delete all entries for the given view
    *
    * @param view
    *   the reference to the composite view
    * @param rev
    *   the revision of the view
    */
  def deleteAll(view: ViewRef, rev: Int): UIO[Unit]

  /**
    * Save a composite restart
    */
  def saveRestart(restart: CompositeRestart): UIO[Unit]

  /**
    * Reset the progress for the rebuild branches
    * @param view
    *   the reference to the composite view
    * @param rev
    *   the revision of the view
    */
  def resetRebuild(view: ViewRef, rev: Int): UIO[Unit]

  /**
    * Detect an eventual restart for the given view
    * @param view
    *   the view reference
    */
  def handleRestarts[A](view: ViewRef): Pipe[Task, A, A]
}

object CompositeProjections {

  private val logger: Logger = Logger[CompositeProjections]

  def apply(compositeRestartStore: CompositeRestartStore, xas: Transactors, query: QueryConfig, batch: BatchConfig)(
      implicit clock: Clock[UIO]
  ): CompositeProjections =
    new CompositeProjections {
      private val projectionStore        = ProjectionStore(xas, query)
      private val compositeProgressStore = new CompositeProgressStore(xas)

      override def progress(view: ViewRef, rev: Int): UIO[CompositeProgress] =
        compositeProgressStore.progress(view, rev).map(CompositeProgress(_))

      override def saveOperation(
          metadata: ProjectionMetadata,
          view: ViewRef,
          rev: Int,
          branch: CompositeBranch,
          progress: ProjectionProgress
      ): Operation =
        Operation.fromFs2Pipe[Unit](
          Projection.persist(
            progress,
            compositeProgressStore.save(view, rev, branch, _),
            projectionStore.saveFailedElems(metadata, _)
          )(batch)
        )

      override def deleteAll(view: ViewRef, rev: Int): UIO[Unit] = compositeProgressStore.deleteAll(view, rev)

      override def saveRestart(restart: CompositeRestart): UIO[Unit] = compositeRestartStore.save(restart)

      override def resetRebuild(view: ViewRef, rev: Int): UIO[Unit] =
        compositeProgressStore.restart(FullRebuild.auto(view.project, view.viewId))

      override def handleRestarts[A](view: ViewRef): Pipe[Task, A, A] = (stream: Stream[Task, A]) => {
        val applyRestart =
          for {
            _    <- Task.delay(logger.debug(s"Acknowledging restart for composite view {}", view))
            head <- compositeRestartStore.head(view)
            _    <- head.traverse { elem =>
                      elem.traverse { restart =>
                        compositeProgressStore.restart(restart) >> compositeRestartStore.acknowledge(elem.offset)
                      }
                    }
          } yield ()

        def restartWhen: Stream[Task, Boolean] =
          Stream
            .awakeEvery[Task](batch.maxInterval)
            .flatMap { _ => Stream.eval(compositeRestartStore.head(view)).map(_.nonEmpty) }
            .evalTap { b => Task.when(b)(applyRestart) }

        stream.interruptWhen(restartWhen).repeat
      }
    }
}
