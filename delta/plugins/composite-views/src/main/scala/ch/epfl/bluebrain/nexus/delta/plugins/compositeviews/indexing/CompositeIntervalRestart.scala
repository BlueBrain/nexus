package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.Clock
import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.routes.CompositeViewsRoutes.RestartProjections
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectsCounts
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger
import monix.bio.{Task, UIO}
import monix.execution.{Cancelable, Scheduler}

import java.time.Instant
import scala.concurrent.duration.MILLISECONDS

/**
  * The async. process for restarting a composite view with cancelable capabilities
  */
final case class CompositeViewCancelableIntervalRestart private[indexing] (
    private val cancelable: Cancelable,
    private val nextInterval: Ref[Task, Option[Instant]]
) {

  /**
    * Stop attempting to continue restarting for next intervals
    */
  def cancel(): Unit = cancelable.cancel()

  /**
    * @return the time of the next scheduled interval restart
    */
  def nextRestart: Task[Option[Instant]] = nextInterval.get
}

trait CompositeIntervalRestart {

  /**
    * Runs the interval restart async. process for restarting the passed composite view at the intervals
    * defined on the view.
    *
    * @param view the composite view
    * @param rev  the revision of the composite view
    */
  def run(view: CompositeView, rev: Long): UIO[CompositeViewCancelableIntervalRestart]
}

object CompositeIntervalRestart {
  implicit private val logger: Logger = Logger[CompositeIntervalRestart.type]
  private[indexing] type RemoteProjectsCounts = RemoteProjectSource => UIO[Option[ProjectCount]]

  def apply(
      projectsCounts: ProjectsCounts,
      restartProjections: RestartProjections,
      deltaClient: DeltaClient
  )(implicit s: Scheduler, clock: Clock[UIO]): CompositeIntervalRestart = {
    val remoteProjectsCounts: RemoteProjectsCounts =
      source =>
        deltaClient
          .projectCount(source)
          .redeem(
            err => {
              val msg =
                s"Error while retrieving the project count for the remote Delta instance '${source.endpoint}' on project '${source.project}'"
              logger.error(msg, err)
              None
            },
            identity
          )
    apply(projectsCounts, restartProjections, remoteProjectsCounts)
  }

  private[indexing] def apply(
      projectsCounts: ProjectsCounts,
      restartProjections: RestartProjections,
      remoteProjectsCounts: RemoteProjectsCounts
  )(implicit s: Scheduler, clock: Clock[UIO]): CompositeIntervalRestart =
    new CompositeIntervalRestart {

      def run(
          view: CompositeView,
          rev: Long
      ): UIO[CompositeViewCancelableIntervalRestart] =
        for {
          ref       <- Ref.of[Task, Option[Instant]](None).logAndDiscardErrors("Generate nextInterval ref")
          cancelable = apply(view, rev, ref).runAsync(_.fold(_ => (), identity))
        } yield CompositeViewCancelableIntervalRestart(cancelable, ref)

      private def apply(
          view: CompositeView,
          rev: Long,
          nextInterval: Ref[Task, Option[Instant]]
      ): UIO[Unit] =
        view.rebuildStrategy match {
          case Some(Interval(intervalRestart)) =>
            def fetchProjectsCounts: UIO[Map[CompositeViewSource, ProjectCount]] = UIO
              .traverse(view.sources.value) {
                case source: ProjectSource       => projectsCounts.get(view.project).map(_.map(source -> _))
                case source: CrossProjectSource  => projectsCounts.get(source.project).map(_.map(source -> _))
                case source: RemoteProjectSource => remoteProjectsCounts(source).map(_.map(source -> _))
              }
              .map(_.flatten.toMap)

            def restart(sources: Set[CompositeViewSource]): UIO[Unit] = {
              val projectionsToRestart = for {
                source     <- sources
                projection <- view.projections.value
              } yield CompositeViews.projectionId(source, projection, rev)._2
              restartProjections(view.id, view.project, projectionsToRestart)
            }

            def loop(currentProjectsCounts: Map[CompositeViewSource, ProjectCount]): UIO[Unit] =
              for {
                currentTime       <- clock.realTime(MILLISECONDS)
                nextInstant        = Instant.ofEpochMilli(currentTime).plusMillis(intervalRestart.toMillis)
                _                 <- nextInterval.set(Some(nextInstant)).logAndDiscardErrors("Set interval")
                _                 <- UIO.sleep(intervalRestart)
                newProjectsCounts <- fetchProjectsCounts
                diffProjectsCounts = (newProjectsCounts.toSet -- currentProjectsCounts.toSet).map(_._1)
                _                 <- UIO.when(diffProjectsCounts.nonEmpty)(restart(diffProjectsCounts))
                _                 <- loop(newProjectsCounts)
              } yield ()

            loop(Map.empty)

          case None => UIO.unit
        }
    }

}
