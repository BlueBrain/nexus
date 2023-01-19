package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphCoordinator.logger
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import monix.bio.Task

/**
  * Coordinates the lifecycle of Blazegraph views as projections
  * @param fetchViews
  *   stream of indexing views
  * @param graphStream
  *   to provide the data feeding the Blazegraph projections
  * @param compilePipeChain
  *   to compile and validate pipechains before running them
  * @param cache
  *   a cache of the current running views
  * @param supervisor
  *   the general supervisor
  */
final class BlazegraphCoordinator private (
    fetchViews: Offset => ElemStream[IndexingViewDef],
    graphStream: GraphResourceStream,
    compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
    cache: KeyValueStore[ViewRef, ActiveViewDef],
    supervisor: Supervisor,
    sink: ActiveViewDef => Sink,
    createNamespace: ActiveViewDef => Task[Unit],
    deleteNamespace: ActiveViewDef => Task[Unit]
) {

  def run(offset: Offset): Stream[Task, Elem[Unit]] = {
    fetchViews(offset).evalMap { elem =>
      elem
        .traverse { v =>
          cache.get(v.ref).flatMap { cachedView =>
            (cachedView, v) match {
              case (Some(cached), active: ActiveViewDef) if cached.projection == active.projection =>
                for {
                  _ <- cache.put(active.ref, active)
                  _ <- Task.delay {
                         logger.info(s"Index ${active.projection} already exists and will not be recreated.")
                       }
                } yield ()
              case (_, active: ActiveViewDef)                                                      =>
                IndexingViewDef
                  .compile(
                    active,
                    compilePipeChain,
                    graphStream,
                    sink(active)
                  )
                  .flatMap { projection =>
                    cleanupCurrent(active.ref) >>
                      supervisor.run(
                        projection,
                        for {
                          _ <- createNamespace(active)
                          _ <- cache.put(active.ref, active)
                        } yield ()
                      )
                  }
              case (_, deprecated: DeprecatedViewDef)                                              =>
                cleanupCurrent(deprecated.ref)
            }
          }
        }
        .onErrorRecover {
          // If the current view does not translate to a projection then we mark it as failed and move along
          case p: ProjectionErr => elem.failed(p)
        }
        .map(_.void)
    }
  }

  private def cleanupCurrent(ref: ViewRef): Task[Unit] =
    cache.get(ref).flatMap {
      case Some(v) =>
        supervisor
          .destroy(
            v.projection,
            for {
              _ <-
                Task.delay(
                  logger.info(
                    s"View '${ref.project}/${ref.viewId}' has been updated or deprecated, cleaning up the current one."
                  )
                )
              _ <- deleteNamespace(v)
              _ <- cache.remove(v.ref)
            } yield ()
          )
          .void
      case None    =>
        Task.delay(
          logger.debug(s"View '${ref.project}/${ref.viewId}' is not referenced yet, cleaning is aborted.")
        )
    }

}

object BlazegraphCoordinator {
  val metadata: ProjectionMetadata = ProjectionMetadata("system", "blazegraph-coordinator", None, None)
  private val logger: Logger       = Logger[BlazegraphCoordinator]

  def apply(
      views: BlazegraphViews,
      graphStream: GraphResourceStream,
      registry: ReferenceRegistry,
      supervisor: Supervisor,
      client: BlazegraphClient,
      batchConfig: BatchConfig
  )(implicit baseUri: BaseUri): Task[BlazegraphCoordinator] =
    apply(
      views.indexingViews,
      graphStream,
      PipeChain.compile(_, registry),
      supervisor,
      (v: ActiveViewDef) => new BlazegraphSink(client, batchConfig.maxElements, batchConfig.maxInterval, v.namespace),
      (v: ActiveViewDef) =>
        client
          .createNamespace(v.namespace)
          .tapError { e =>
            Task.delay(
              logger.error(s"Namespace for view '${v.ref.project}/${v.ref.viewId}' could not be created.", e)
            )
          }
          .void,
      (v: ActiveViewDef) => client.deleteNamespace(v.namespace).void
    )

  def apply(
      fetchViews: Offset => ElemStream[IndexingViewDef],
      graphStream: GraphResourceStream,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      supervisor: Supervisor,
      sink: ActiveViewDef => Sink,
      createIndex: ActiveViewDef => Task[Unit],
      deleteIndex: ActiveViewDef => Task[Unit]
  ): Task[BlazegraphCoordinator] =
    for {
      cache      <- KeyValueStore[ViewRef, ActiveViewDef]()
      coordinator = new BlazegraphCoordinator(
                      fetchViews,
                      graphStream,
                      compilePipeChain,
                      cache,
                      supervisor,
                      sink,
                      createIndex,
                      deleteIndex
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
