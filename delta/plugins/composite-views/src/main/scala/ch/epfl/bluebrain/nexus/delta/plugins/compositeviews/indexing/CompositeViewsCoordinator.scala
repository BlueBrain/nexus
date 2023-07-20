package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import fs2.Stream
import monix.bio.Task

sealed trait CompositeViewsCoordinator

object CompositeViewsCoordinator {

  /** If indexing is disabled we can only log */
  final private case object Noop extends CompositeViewsCoordinator {
    def log: Task[Unit] = logger.info("Composite Views indexing has been disabled via config")
  }

  /**
    * Coordinates the lifecycle of composite views as projections
    * @param fetchViews
    *   to fetch the composite views
    * @param cache
    *   a cache of the current running views
    * @param supervisor
    *   the general supervisor
    * @param lifecycle
    *   defines how to init, run and destroy the projection related to the view
    */
  final private class Active(
      fetchViews: Offset => ElemStream[CompositeViewDef],
      cache: KeyValueStore[ViewRef, ActiveViewDef],
      supervisor: Supervisor,
      lifecycle: CompositeProjectionLifeCycle
  ) extends CompositeViewsCoordinator {

    def run(offset: Offset): Stream[Task, Elem[Unit]] = {
      fetchViews(offset).evalMap {
        _.traverse {
          case active: ActiveViewDef =>
            for {
              // Compiling and validating the view
              projection <- lifecycle.build(active)
              // Stopping the previous version
              _          <- cleanupCurrent(cache, active, destroy)
              // Init, register and run the new version
              _          <- supervisor.run(
                              projection,
                              for {
                                _ <- lifecycle.init(active)
                                _ <- cache.put(active.ref, active)
                              } yield ()
                            )
            } yield ()
          case d: DeprecatedViewDef  => cleanupCurrent(cache, d, destroy)
        }
      }
    }

    private def destroy(active: ActiveViewDef) =
      supervisor
        .destroy(
          active.projection,
          for {
            _ <- lifecycle.destroy(active)
            _ <- cache.remove(active.ref)
          } yield ()
        )
        .void

  }

  private val metadata: ProjectionMetadata = ProjectionMetadata("system", "composite-views-coordinator", None, None)
  private val logger: Logger               = Logger[CompositeViewsCoordinator]

  def cleanupCurrent(
      cache: KeyValueStore[ViewRef, ActiveViewDef],
      viewDef: CompositeViewDef,
      destroy: ActiveViewDef => Task[Unit]
  ): Task[Unit] = {
    val ref = viewDef.ref
    cache.get(ref).flatMap { cachedOpt =>
      (cachedOpt, viewDef) match {
        case (Some(cached), active: ActiveViewDef) if cached.projection == active.projection =>
          logger.info(s"Projection '${cached.projection}' is already running and will not be recreated.")
        case (Some(cached), _: ActiveViewDef)                                                =>
          logger.info(s"View '${ref.project}/${ref.viewId}' has been updated, cleaning up the current one.") >>
            destroy(cached)
        case (Some(cached), _: DeprecatedViewDef)                                            =>
          logger.info(s"View '${ref.project}/${ref.viewId}' has been deprecated, cleaning up the current one.") >>
            destroy(cached)
        case (None, _)                                                                       =>
          logger.debug(s"View '${ref.project}/${ref.viewId}' is not referenced yet, cleaning is aborted.")
      }
    }
  }

  def apply(
      compositeViews: CompositeViews,
      supervisor: Supervisor,
      builder: CompositeProjectionLifeCycle,
      config: CompositeViewsConfig
  ): Task[CompositeViewsCoordinator] = {
    if (config.indexingEnabled) {
      for {
        cache      <- KeyValueStore[ViewRef, ActiveViewDef]()
        coordinator = new Active(
                        compositeViews.views,
                        cache,
                        supervisor,
                        builder
                      )
        _          <- supervisor.run(
                        CompiledProjection.fromStream(
                          metadata,
                          ExecutionStrategy.EveryNode,
                          coordinator.run
                        )
                      )
      } yield coordinator
    } else {
      Noop.log.as(Noop)
    }
  }

}
