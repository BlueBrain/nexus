package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.http.scaladsl.model.StatusCodes
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
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

sealed trait ElasticSearchCoordinator

object ElasticSearchCoordinator {

  /** If indexing is disabled we can only log */
  final private case object Noop extends ElasticSearchCoordinator {
    def log: Task[Unit] =
      Task.delay {
        logger.info("Elasticsearch indexing has been disabled via config")
      }
  }

  /**
    * Coordinates the lifecycle of Elasticsearch views as projections
    * @param fetchViews
    *   stream of indexing views
    * @param graphStream
    *   to provide the data feeding the Elasticsearch projections
    * @param compilePipeChain
    *   to compile and validate pipechains before running them
    * @param cache
    *   a cache of the current running views
    * @param supervisor
    *   the general supervisor
    */
  final private class ActiveCoordinator(
      fetchViews: Offset => ElemStream[IndexingViewDef],
      graphStream: GraphResourceStream,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      cache: KeyValueStore[ViewRef, ActiveViewDef],
      supervisor: Supervisor,
      sink: ActiveViewDef => Sink,
      createIndex: ActiveViewDef => Task[Unit],
      deleteIndex: ActiveViewDef => Task[Unit]
  )(implicit cr: RemoteContextResolution)
      extends ElasticSearchCoordinator {

    def run(offset: Offset): Stream[Task, Elem[Unit]] = {
      fetchViews(offset).evalMap { elem =>
        elem
          .traverse { v =>
            cache.get(v.ref).flatMap { cachedView =>
              (cachedView, v) match {
                case (Some(cached), active: ActiveViewDef) if cached.index == active.index =>
                  for {
                    _ <- cache.put(active.ref, active)
                    _ <- Task.delay(
                           logger.info(s"Index ${active.index} already exists and will not be recreated.")
                         )
                  } yield ()
                case (cached, active: ActiveViewDef)                                       =>
                  compile(active)
                    .flatMap { projection =>
                      cleanupCurrent(cached, active.ref) >>
                        supervisor.run(
                          projection,
                          for {
                            _ <- createIndex(active)
                            _ <- cache.put(active.ref, active)
                          } yield ()
                        )
                    }
                case (cached, deprecated: DeprecatedViewDef)                               =>
                  cleanupCurrent(cached, deprecated.ref)
              }
            }
          }
          .onErrorRecover {
            // If the current view does not translate to a projection or if there is a problem
            // with the mapping with the mapping / setting then we mark it as failed and move along
            case p: ProjectionErr                                                   => elem.failed(p)
            case http: HttpClientStatusError if http.code == StatusCodes.BadRequest => elem.failed(http)
          }
          .map(_.void)
      }
    }

    private def cleanupCurrent(cached: Option[ActiveViewDef], ref: ViewRef): Task[Unit] =
      cached match {
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
                _ <- deleteIndex(v)
                _ <- cache.remove(v.ref)
              } yield ()
            )
            .void
        case None    =>
          Task.delay(
            logger.debug(s"View '${ref.project}/${ref.viewId}' is not referenced yet, cleaning is aborted.")
          )
      }

    private def compile(active: ActiveViewDef): Task[CompiledProjection] =
      IndexingViewDef.compile(active, compilePipeChain, graphStream, sink(active))

  }

  val metadata: ProjectionMetadata = ProjectionMetadata("system", "elasticsearch-coordinator", None, None)
  val logger: Logger               = Logger[ElasticSearchCoordinator]

  def apply(
      views: ElasticSearchViews,
      graphStream: GraphResourceStream,
      registry: ReferenceRegistry,
      supervisor: Supervisor,
      client: ElasticSearchClient,
      batchConfig: BatchConfig,
      config: ElasticSearchViewsConfig
  )(implicit cr: RemoteContextResolution): Task[ElasticSearchCoordinator] = {
    if (config.indexingEnabled) {
      apply(
        views.indexingViews,
        graphStream,
        PipeChain.compile(_, registry),
        supervisor,
        (v: ActiveViewDef) =>
          ElasticSearchSink.states(client, batchConfig.maxElements, batchConfig.maxInterval, v.index, Refresh.False),
        (v: ActiveViewDef) =>
          client
            .createIndex(v.index, Some(v.mapping), Some(v.settings))
            .tapError { e =>
              Task.delay(
                logger.error(s"Index for view '${v.ref.project}/${v.ref.viewId}' could not be created.", e)
              )
            }
            .void,
        (v: ActiveViewDef) => client.deleteIndex(v.index).void
      )
    } else {
      Noop.log.as(Noop)
    }
  }

  def apply(
      fetchViews: Offset => ElemStream[IndexingViewDef],
      graphStream: GraphResourceStream,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      supervisor: Supervisor,
      sink: ActiveViewDef => Sink,
      createIndex: ActiveViewDef => Task[Unit],
      deleteIndex: ActiveViewDef => Task[Unit]
  )(implicit cr: RemoteContextResolution): Task[ElasticSearchCoordinator] =
    for {
      cache      <- KeyValueStore[ViewRef, ActiveViewDef]()
      coordinator = new ActiveCoordinator(
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
