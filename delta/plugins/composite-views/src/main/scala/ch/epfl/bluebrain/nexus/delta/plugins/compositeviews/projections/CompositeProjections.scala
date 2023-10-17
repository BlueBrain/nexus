package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.Clock
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.{CompositeProgressStore, CompositeRestartStore}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingViewRef, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.FailedElemLogStore
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/**
  * Handles projection operation for composite views
  */
trait CompositeProjections {

  /**
    * Return the composite progress for the given view
    */
  def progress(view: IndexingViewRef): UIO[CompositeProgress]

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
  def saveOperation(view: ActiveViewDef, branch: CompositeBranch, progress: ProjectionProgress): Operation

  /**
    * Delete all entries for the given view
    */
  def deleteAll(view: IndexingViewRef): UIO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def scheduleFullRestart(view: ViewRef)(implicit subject: Subject): UIO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def scheduleFullRebuild(view: ViewRef)(implicit subject: Subject): UIO[Unit]

  /**
    * Schedules a rebuild restarting indexing process for the given target while keeping the progress for the sources
    * and the other projections
    */
  def schedulePartialRebuild(view: ViewRef, target: Iri)(implicit subject: Subject): UIO[Unit]

  /**
    * Reset the progress for the rebuild branches
    */
  def resetRebuild(view: ViewRef): UIO[Unit]

  /**
    * Reset the progress for the rebuild branches
    */
  def partialRebuild(view: ViewRef, target: Iri): UIO[Unit]

  /**
    * Detect an eventual restart for the given view
    * @param view
    *   the view reference
    */
  def handleRestarts[A](view: ViewRef): Pipe[Task, A, A]
}

object CompositeProjections {

  private val logger: Logger = Logger[CompositeProjections]

  def apply(
      compositeRestartStore: CompositeRestartStore,
      xas: Transactors,
      query: QueryConfig,
      batch: BatchConfig,
      restartCheckInterval: FiniteDuration
  )(implicit
      clock: Clock[UIO]
  ): CompositeProjections =
    new CompositeProjections {
      private val failedElemLogStore     = FailedElemLogStore(xas, query)
      private val compositeProgressStore = new CompositeProgressStore(xas)

      override def progress(view: IndexingViewRef): UIO[CompositeProgress] =
        compositeProgressStore.progress(view).map(CompositeProgress(_))

      override def saveOperation(
          view: ActiveViewDef,
          branch: CompositeBranch,
          progress: ProjectionProgress
      ): Operation =
        OperationF.fromFs2Pipe[Task, Unit](
          Projection.persist(
            progress,
            compositeProgressStore.save(view.indexingRef, branch, _),
            failedElemLogStore.save(view.metadata, _)
          )(batch)
        )

      override def deleteAll(view: IndexingViewRef): UIO[Unit] = compositeProgressStore.deleteAll(view)

      override def scheduleFullRestart(view: ViewRef)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(FullRestart(view, _, subject))

      override def scheduleFullRebuild(view: ViewRef)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(FullRebuild(view, _, subject))

      override def schedulePartialRebuild(view: ViewRef, target: Iri)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(PartialRebuild(view, target, _, subject))

      private def scheduleRestart(f: Instant => CompositeRestart) =
        for {
          now    <- IOUtils.instant
          restart = f(now)
          _      <- logger.info(s"Scheduling a ${restart.getClass.getSimpleName} from composite view '${restart.view}'")
          _      <- compositeRestartStore.save(restart)
        } yield ()

      override def resetRebuild(view: ViewRef): UIO[Unit] =
        logger.debug(s"Automatically reset rebuild offsets for composite view '$view'") >>
          compositeProgressStore.restart(FullRebuild.auto(view))

      override def partialRebuild(view: ViewRef, target: Iri): UIO[Unit] =
        logger.debug(s"Automatically reset rebuild offsets for projection $target of composite view '$view'") >>
          compositeProgressStore.restart(PartialRebuild.auto(view, target))

      override def handleRestarts[A](view: ViewRef): Pipe[Task, A, A] = (stream: Stream[Task, A]) => {
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

        def restartWhen: Stream[Task, Boolean] =
          Stream
            .awakeEvery[Task](restartCheckInterval)
            .flatMap { _ => Stream.eval(compositeRestartStore.head(view)).map(_.nonEmpty) }

        stream.interruptWhen(restartWhen).onFinalize(applyRestart).repeat
      }
    }
}
