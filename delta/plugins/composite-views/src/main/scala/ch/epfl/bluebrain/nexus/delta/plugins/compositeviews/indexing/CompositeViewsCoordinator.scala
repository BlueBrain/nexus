package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewsCoordinator.cleanupCurrent
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeGraphStream
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task

/**
  * Coordinates the lifecycle of composite views as projections
  * @param fetchViews
  *   to fetch the composite views
  * @param cache
  *   a cache of the current running views
  * @param supervisor
  *   the general supervisor
  * @param compilePipeChain
  *   how to compile pipe chains
  * @param graphStream
  *   to provide the data feeding the indexing
  * @param buildSpaces
  *   to create the necessary namespaces / indices and the pipes/sinks to interact with them
  * @param compositeProjections
  *   to fetch/save progress as well as handling restarts
  */
final class CompositeViewsCoordinator(
    fetchViews: Offset => ElemStream[CompositeViewDef],
    cache: KeyValueStore[ViewRef, ActiveViewDef],
    supervisor: Supervisor,
    compilePipeChain: PipeChain.Compile,
    graphStream: CompositeGraphStream,
    buildSpaces: ActiveViewDef => CompositeSpaces,
    compositeProjections: CompositeProjections
)(implicit cr: RemoteContextResolution) {

  def run(offset: Offset): Stream[Task, Elem[Unit]] = {
    fetchViews(offset).evalMap {
      _.traverse {
        case active: ActiveViewDef =>
          val spaces = buildSpaces(active)
          for {
            // Compiling and validating the view
            projection <- CompositeViewDef.compile(active, spaces, compilePipeChain, graphStream, compositeProjections)
            // Stopping the previous version
            _          <- cleanupCurrent(cache, active, destroy)
            // Init, register and run the new version
            _          <- supervisor.run(
                            projection,
                            for {
                              _ <- spaces.init
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
          _ <- buildSpaces(active).destroy
          _ <- compositeProjections.deleteAll(active.ref, active.rev)
          _ <- cache.remove(active.ref)
        } yield ()
      )
      .void

}

object CompositeViewsCoordinator {

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
          Task.delay(logger.info(s"Projection '${cached.projection}' is already running and will not be recreated."))
        case (Some(cached), _: ActiveViewDef)                                                              =>
          Task.delay(
            logger.info(s"View '${ref.project}/${ref.viewId}' has been updated, cleaning up the current one.")
          ) >>
            destroy(cached)
        case (Some(cached), _: DeprecatedViewDef)                                                          =>
          Task.delay(
            logger.info(s"View '${ref.project}/${ref.viewId}' has been deprecated, cleaning up the current one.")
          ) >>
            destroy(cached)
        case (None, _)                                                                                     =>
          Task.delay(
            logger.debug(s"View '${ref.project}/${ref.viewId}' is not referenced yet, cleaning is aborted.")
          )
      }
    }
  }

  def apply(
      compositeViews: CompositeViews,
      supervisor: Supervisor,
      compilePipeChain: PipeChain.Compile,
      graphStream: CompositeGraphStream,
      buildSpaces: ActiveViewDef => CompositeSpaces,
      compositeProjections: CompositeProjections
  )(implicit cr: RemoteContextResolution): Task[CompositeViewsCoordinator] =
    for {
      cache      <- KeyValueStore[ViewRef, ActiveViewDef]()
      coordinator = new CompositeViewsCoordinator(
                      compositeViews.views,
                      cache,
                      supervisor,
                      compilePipeChain: PipeChain.Compile,
                      graphStream: CompositeGraphStream,
                      buildSpaces: ActiveViewDef => CompositeSpaces,
                      compositeProjections: CompositeProjections
                    )
      _          <- supervisor.run(
                      CompiledProjection.fromStream(
                        metadata,
                        ExecutionStrategy.EveryNode,
                        coordinator.run
                      )
                    )
    } yield coordinator

}
