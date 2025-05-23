package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.LocalCache
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.config.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*

sealed trait BlazegraphCoordinator

object BlazegraphCoordinator {

  /** If indexing is disabled we can only log */
  final private case object Noop extends BlazegraphCoordinator {
    def log: IO[Unit] =
      logger.info("Blazegraph indexing has been disabled via config")
  }

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
  final private class Active(
      fetchViews: Offset => SuccessElemStream[IndexingViewDef],
      graphStream: GraphResourceStream,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      cache: LocalCache[ViewRef, ActiveViewDef],
      supervisor: Supervisor,
      sink: ActiveViewDef => Sink,
      createNamespace: ActiveViewDef => IO[Unit],
      deleteNamespace: ActiveViewDef => IO[Unit]
  ) extends BlazegraphCoordinator {

    def run(offset: Offset): ElemStream[Unit] = {
      fetchViews(offset).evalMap { elem =>
        elem
          .traverse { v =>
            cache.get(v.ref).flatMap { cachedView =>
              (cachedView, v) match {
                case (Some(cached), active: ActiveViewDef) if cached.projection == active.projection =>
                  for {
                    _ <- cache.put(active.ref, active)
                    _ <- logger.info(s"Index ${active.projection} already exists and will not be recreated.")
                  } yield ()
                case (cached, active: ActiveViewDef)                                                 =>
                  compile(active)
                    .flatMap { projection =>
                      cleanupCurrent(cached, active.ref) >>
                        supervisor.run(
                          projection,
                          for {
                            _ <- createNamespace(active)
                            _ <- cache.put(active.ref, active)
                          } yield ()
                        )
                    }
                case (cached, deprecated: DeprecatedViewDef)                                         =>
                  cleanupCurrent(cached, deprecated.ref)
              }
            }
          }
          .recover {
            // If the current view does not translate to a projection then we mark it as failed and move along
            case p: ProjectionErr => elem.failed(p)
          }
          .map(_.void)
      }
    }

    private def cleanupCurrent(cached: Option[ActiveViewDef], ref: ViewRef): IO[Unit] =
      cached match {
        case Some(v) =>
          supervisor
            .destroy(
              v.projection,
              for {
                _ <-
                  logger.info(
                    s"View '${ref.project}/${ref.viewId}' has been updated or deprecated, cleaning up the current one."
                  )
                _ <- deleteNamespace(v)
                _ <- cache.remove(v.ref)
              } yield ()
            )
            .void
        case None    =>
          logger.debug(s"View '${ref.project}/${ref.viewId}' is not referenced yet, cleaning is aborted.")
      }

    private def compile(active: ActiveViewDef): IO[CompiledProjection] =
      IndexingViewDef.compile(active, compilePipeChain, graphStream, sink(active))

  }

  val metadata: ProjectionMetadata = ProjectionMetadata("system", "blazegraph-coordinator", None, None)
  private val logger               = Logger[BlazegraphCoordinator]

  def apply(
      views: BlazegraphViews,
      graphStream: GraphResourceStream,
      registry: ReferenceRegistry,
      supervisor: Supervisor,
      client: SparqlClient,
      config: BlazegraphViewsConfig
  )(implicit baseUri: BaseUri): IO[BlazegraphCoordinator] =
    if (config.indexingEnabled) {
      apply(
        views.indexingViews,
        graphStream,
        PipeChain.compile(_, registry),
        supervisor,
        (v: ActiveViewDef) => SparqlSink(client, config.retryStrategy, config.batch, v.namespace),
        (v: ActiveViewDef) =>
          client
            .createNamespace(v.namespace)
            .onError { case e =>
              logger.error(e)(s"Namespace for view '${v.ref.project}/${v.ref.viewId}' could not be created.")
            }
            .void,
        (v: ActiveViewDef) => client.deleteNamespace(v.namespace).void
      )
    } else {
      Noop.log.as(Noop)
    }

  def apply(
      fetchViews: Offset => SuccessElemStream[IndexingViewDef],
      graphStream: GraphResourceStream,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      supervisor: Supervisor,
      sink: ActiveViewDef => Sink,
      createIndex: ActiveViewDef => IO[Unit],
      deleteIndex: ActiveViewDef => IO[Unit]
  ): IO[BlazegraphCoordinator] =
    for {
      cache      <- LocalCache[ViewRef, ActiveViewDef]()
      coordinator = new Active(
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
