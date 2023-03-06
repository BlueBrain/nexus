package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections

import cats.effect.Clock
import cats.syntax.traverse._
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeRestart.{FullRebuild, FullRestart, PartialRebuild}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.store.{CompositeProgressStore, CompositeRestartStore}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{BatchConfig, QueryConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}

import concurrent.duration.FiniteDuration

import java.time.Instant

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
    * Return the composite progress for the given view
    * @param project
    *   the project where the view belongs
    * @param id
    *   the identifier of the view
    * @param rev
    *   the revision of the view
    */
  def progress(project: ProjectRef, id: Iri, rev: Int): UIO[CompositeProgress] =
    progress(ViewRef(project, id), rev)

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
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def fullRestart(project: ProjectRef, id: Iri)(implicit subject: Subject): UIO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def fullRebuild(project: ProjectRef, id: Iri)(implicit subject: Subject): UIO[Unit]

  /**
    * Schedules a full rebuild restarting indexing process for all targets while keeping the sources (and the
    * intermediate Sparql space) progress
    */
  def partialRebuild(project: ProjectRef, id: Iri, target: Iri)(implicit subject: Subject): UIO[Unit]

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

      override def fullRestart(project: ProjectRef, id: Iri)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(FullRestart(project, id, _, subject))

      override def fullRebuild(project: ProjectRef, id: Iri)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(FullRebuild(project, id, _, subject))

      override def partialRebuild(project: ProjectRef, id: Iri, target: Iri)(implicit subject: Subject): UIO[Unit] =
        scheduleRestart(PartialRebuild(project, id, target, _, subject))

      private def scheduleRestart(f: Instant => CompositeRestart) =
        for {
          now    <- IOUtils.instant
          restart = f(now)
          _      <-
            UIO.delay(
              logger.info(
                s"Scheduling a ${restart.getClass.getSimpleName} from composite view ${restart.id} in project ${restart.project}"
              )
            )
          _      <- compositeRestartStore.save(restart)
        } yield ()

      override def resetRebuild(view: ViewRef, rev: Int): UIO[Unit] =
        UIO.delay(
          logger.debug(
            s"Automatically reset rebuild offsets for composite view {} in project {}",
            view.viewId,
            view.project
          )
        ) >>
          compositeProgressStore.restart(FullRebuild.auto(view.project, view.viewId))

      override def handleRestarts[A](view: ViewRef): Pipe[Task, A, A] = (stream: Stream[Task, A]) => {
        val applyRestart =
          for {
            head <- compositeRestartStore.head(view)
            _    <- head.traverse { elem =>
                      elem.traverse { restart =>
                        Task.delay(
                          logger.info(s"Acknowledging ${restart.getClass.getSimpleName} for composite view {}", view)
                        ) >>
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
