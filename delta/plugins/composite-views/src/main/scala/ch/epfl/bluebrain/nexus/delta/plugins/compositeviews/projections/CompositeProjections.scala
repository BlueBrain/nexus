package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.concurrent.Ref
import cats.effect.{Clock, ContextShift, IO, Timer}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOInstant
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.{CompositeProgressStore, CompositeRestartStore}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch.Run.{Main, Rebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingViewRef, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.FailedElemLogStore
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.{Pipe, Stream}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
  * Handles projection operation for composite views
  */
trait CompositeProjections {

  /**
    * Return the composite progress for the given view
    */
  def progress(view: IndexingViewRef): IO[CompositeProgress]

  /**
    * Creates the save [[Operation]] for a given branch of the specified view.
    *
    * Saves both the progress and the errors
    *
    * @param view
    *   view for which to save the progress
    * @param branch
    *   composite branch for which to save the progress
    * @param progress
    *   offset to save
    * @param mainUpdatedRef
    *   [[Ref]] used to indicate that there was a progress change on the main branch
    */
  def saveOperation(
      view: ActiveViewDef,
      branch: CompositeBranch,
      progress: ProjectionProgress,
      mainUpdatedRef: Ref[IO, Boolean]
  ): Operation

  /**
    * Delete all entries for the given view
    */
  def deleteAll(view: IndexingViewRef): IO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def scheduleFullRestart(view: ViewRef)(implicit subject: Subject): IO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def scheduleFullRebuild(view: ViewRef)(implicit subject: Subject): IO[Unit]

  /**
    * Schedules a rebuild restarting indexing process for the given target while keeping the progress for the sources
    * and the other projections
    */
  def schedulePartialRebuild(view: ViewRef, target: Iri)(implicit subject: Subject): IO[Unit]

  /**
    * Reset the progress for the rebuild branches
    */
  def resetRebuild(view: ViewRef): IO[Unit]

  /**
    * Reset the progress for the rebuild branches
    */
  def partialRebuild(view: ViewRef, target: Iri): IO[Unit]

  /**
    * Detect an eventual restart for the given view
    * @param view
    *   the view reference
    */
  def handleRestarts[A](view: ViewRef): Pipe[IO, A, A]
}

object CompositeProjections {

  private val logger = Logger[CompositeProjections]

  def apply(
      compositeRestartStore: CompositeRestartStore,
      xas: Transactors,
      query: QueryConfig,
      batch: BatchConfig,
      restartCheckInterval: FiniteDuration
  )(implicit clock: Clock[IO], timer: Timer[IO], cs: ContextShift[IO]): CompositeProjections =
    new CompositeProjections {
      private val failedElemLogStore     = FailedElemLogStore(xas, query)
      private val compositeProgressStore = new CompositeProgressStore(xas)

      override def progress(view: IndexingViewRef): IO[CompositeProgress] =
        compositeProgressStore.progress(view).map(CompositeProgress(_))

      override def saveOperation(
          view: ActiveViewDef,
          branch: CompositeBranch,
          progress: ProjectionProgress,
          mainUpdatedRef: Ref[IO, Boolean]
      ): Operation = {

        val signalMainHasBeenUpdated = branch.run match {
          case Main    => mainUpdatedRef.update(_ => true)
          case Rebuild => IO.unit
        }

        Operation.fromFs2Pipe[Unit](
          Projection.persist(
            progress,
            compositeProgressStore.save(view.indexingRef, branch, _) >> signalMainHasBeenUpdated,
            failedElemLogStore.save(view.metadata, _)
          )(batch, timer, cs)
        )
      }

      override def deleteAll(view: IndexingViewRef): IO[Unit] = compositeProgressStore.deleteAll(view)

      override def scheduleFullRestart(view: ViewRef)(implicit subject: Subject): IO[Unit] =
        scheduleRestart(FullRestart(view, _, subject))

      override def scheduleFullRebuild(view: ViewRef)(implicit subject: Subject): IO[Unit] =
        scheduleRestart(FullRebuild(view, _, subject))

      override def schedulePartialRebuild(view: ViewRef, target: Iri)(implicit subject: Subject): IO[Unit] =
        scheduleRestart(PartialRebuild(view, target, _, subject))

      private def scheduleRestart(f: Instant => CompositeRestart) =
        for {
          now    <- IOInstant.now
          restart = f(now)
          _      <- logger.info(s"Scheduling a ${restart.getClass.getSimpleName} from composite view '${restart.view}'")
          _      <- compositeRestartStore.save(restart)
        } yield ()

      override def resetRebuild(view: ViewRef): IO[Unit] =
        logger.debug(s"Automatically reset rebuild offsets for composite view '$view'") >>
          compositeProgressStore.restart(FullRebuild.auto(view))

      override def partialRebuild(view: ViewRef, target: Iri): IO[Unit] =
        logger.debug(s"Automatically reset rebuild offsets for projection $target of composite view '$view'") >>
          compositeProgressStore.restart(PartialRebuild.auto(view, target))

      override def handleRestarts[A](view: ViewRef): Pipe[IO, A, A] = (stream: Stream[IO, A]) => {
        val applyRestart =
          for {
            head <- compositeRestartStore.head(view)
            _    <- head.traverse { elem =>
                      elem.traverse { restart =>
                        logger.info(s"Acknowledging ${restart.getClass.getSimpleName} for composite view $view") >>
                          compositeProgressStore.restart(restart) >> compositeRestartStore.acknowledge(elem.offset)
                      }
                    }
          } yield ()

        def restartWhen: Stream[IO, Boolean] =
          Stream
            .awakeEvery[IO](restartCheckInterval)
            .flatMap { _ => Stream.eval(compositeRestartStore.head(view)).map(_.nonEmpty) }

        stream.interruptWhen(restartWhen).onFinalize(applyRestart).repeat
      }
    }
}
