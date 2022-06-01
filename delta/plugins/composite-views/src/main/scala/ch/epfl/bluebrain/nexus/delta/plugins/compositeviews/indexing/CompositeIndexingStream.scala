package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlQueryResponseType.SparqlNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingStreamEntry
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingCoordinator.CompositeIndexingController
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingStream._
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{idTemplating, ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{CrossProjectSource, ProjectSource, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{compositeViewType, CompositeView, CompositeViewProjection, CompositeViewSource}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.ProjectsCounts
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamBehaviour.Restart
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewData.IndexingData
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.CompositeViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import com.typesafe.scalalogging.Logger
import fs2.{Chunk, Pipe, Stream}
import monix.bio.{IO, Task, UIO}
import monix.execution.Scheduler

import java.time.Instant
import java.util.regex.Pattern.quote
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.math.Ordering.Implicits._

/**
  * Defines how to build a stream for a CompositeView
  */
final class CompositeIndexingStream(
    esConfig: ExternalIndexingConfig,
    esClient: ElasticSearchClient,
    blazeConfig: ExternalIndexingConfig,
    blazeClient: BlazegraphClient,
    cache: ProgressesCache,
    projectsCounts: ProjectsCounts,
    remoteProjectsCounts: RemoteProjectsCounts,
    restartProjections: RestartProjections,
    projections: Projection[Unit],
    indexingSource: IndexingSource,
    remoteIndexingSource: RemoteIndexingSource
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler, clock: Clock[UIO])
    extends IndexingStream[CompositeView] {

  /**
    * Builds a stream from a composite view and a strategy. There are several stages involved:
    *
    * <ol>
    *
    * <li>The necessary indices are created, cleanup is performed (from previous indices, when necessary) and the
    * progress for each projection is computed (the computed progress depends on the passed ''strategy.progress'').</li>
    *
    * <li>A stream is built for every source from the ''indexingSource'' from the progress taken in step 1). For each of
    * those sources, the resources are converted to a Graph and indexed into the common Blazegraph namespace, unless
    * ''strategy.progress'' is PartialRestart. In that case, the indexing to Blazegraph will be skipped for already
    * indexed resources.</li>
    *
    * <li>Each of the source streams is broadcast to as many projections as the composite view has using
    * ''broadcastThrough''.</li>
    *
    * <li>For each projection pipe, the common Blazegraph namespace is queried, the results are adapted and then indexed
    * into the appropriate projection.</li>
    *
    * <li>All the source streams are merged.</li>
    *
    * <li>If the composite view has a rebuild strategy, a new stream is created. For every interval duration fetches the
    * projects counts and compares them to the previous projects counts. When counts are different, it restarts the
    * stream.</li>
    *
    * <li>The stream on 6) is merged with the stream on 5).</li>
    *
    * </ol>
    *
    * @param view
    *   the [[ViewIndex]]
    * @param strategy
    *   the strategy to build a stream
    */
  override def apply(
      view: ViewIndex[CompositeView],
      strategy: IndexingStream.ProgressStrategy
  ): Task[Stream[Task, Unit]] = Task.delay {
    val stream = Stream
      .eval {
        // evaluates strategy and set/get the appropriate progress
        createIndices(view) >> handleProgress(strategy, view)
      }
      .flatMap { progressMap =>
        val streams = view.value.sources.value.map { source =>
          val sourcePId                                                           = sourceProjection(source, view.rev)
          val progress                                                            = progressMap(sourcePId)
          // fetches the source stream
          val stream: Stream[Task, Chunk[Message[BlazegraphIndexingStreamEntry]]] = source match {
            case s: ProjectSource       =>
              indexingSource(view.projectRef, progress.offset, s.resourceTag).evalMapValue(
                BlazegraphIndexingStreamEntry.fromEventExchange(_)
              )
            case s: CrossProjectSource  =>
              indexingSource(s.project, progress.offset, s.resourceTag).evalMapValue(
                BlazegraphIndexingStreamEntry.fromEventExchange(_)
              )
            case s: RemoteProjectSource =>
              remoteIndexingSource(s, progress.offset)
          }
          // Converts the resource to a graph we are interested when indexing into the common blazegraph namespace
          stream
            .evalMapFilterValue { res =>
              res
                .writeOrNone(
                  source.resourceSchemas,
                  source.resourceTypes,
                  source.includeDeprecated,
                  includeMetadata = true
                )
                .map(_.map(q => res -> q))
            }
            .runAsyncUnit(
              bulkResource =>
                // Pushes DROP/REPLACE queries to Blazegraph common namespace
                IO.when(bulkResource.nonEmpty)(blazeClient.bulk(view.index, bulkResource.map(_._2))),
              Message.filterOffset(progress.value.offset)
            )
            .mapValue { case (res, _) => res }
            .broadcastThrough(view.value.projections.value.map { projection =>
              val pId = projectionId(sourcePId, projection, view.rev)
              projectionPipe(view, pId, projection, progressMap(pId).void)
            }.toSeq: _*)

        }
        streams.foldLeft[Stream[Task, Unit]](Stream.empty)(_ merge _)
      }
    view.value.rebuildStrategy match {
      case Some(Interval(duration)) =>
        stream.merge(
          // creates a stream that every duration interval (starting at 0) fetches the number of events on each source
          streamRepeat(fetchProjectsCounts(view.value), duration)
            // compares the number of events for each source in the lapse of a duration interval
            // if we are in the first interval restart of the view, we always trigger restart
            .evalScan((Map.empty[CompositeViewSource, ProjectCount], Set.empty[CompositeViewSource])) {
              case ((prev, _), cur) if prev.isEmpty =>
                UIO.pure((cur, Set.empty)) // Skip first
              case ((prev, _), cur) =>
                clock
                  .realTime(MILLISECONDS)
                  .map {
                    case millis if isOnFirstIntervalRestart(view, duration)(Instant.ofEpochMilli(millis)) => cur.toSet
                    case _                                                                                => cur.toSet -- prev.toSet
                  }
                  .map { diffSources =>
                    (cur, diffSources.map { case (source, _) => source })
                  }
            }
            .collect { case (_, diffSources) if diffSources.nonEmpty => diffSources }
            // restarts the stream with a PartialRestart strategy on the projections where there was a diff on number of events
            .evalMap { diffSources =>
              triggerRestartProjections(view, diffSources)
            }
        )

      case None => stream
    }
  }

  private def projectionPipe(
      view: ViewIndex[CompositeView],
      pId: CompositeViewProjectionId,
      projection: CompositeViewProjection,
      progress: ProjectionProgress[Unit]
  ): Pipe[Task, Chunk[Message[BlazegraphIndexingStreamEntry]], Unit] = {
    implicit val metricsConfig: KamonMetricsConfig = ViewIndex.metricsConfig(
      view,
      compositeViewType,
      Map(
        "projectionId"   -> pId.projectionId.value,
        "sourceId"       -> pId.sourceId,
        "projectionType" -> projection.tpe.tpe.toString
      )
    )
    val cfg                                        = indexingConfig(projection)
    _.collectSomeValue { res =>
      res
        .deleteCandidate(projection.resourceSchemas, projection.resourceTypes, projection.includeDeprecated)
        .map(res -> _)
    }.evalMapValue {
      case (BlazegraphIndexingStreamEntry(resource: IndexingData), deleteCandidate) if !deleteCandidate =>
        // Run projection query against common blazegraph namespace
        for {
          ntriples       <- blazeClient.query(Set(view.index), replaceId(projection.query, resource.id), SparqlNTriples)
          graphResult    <- Task.fromEither(Graph(ntriples.value.copy(rootNode = resource.id)))
          rootGraphResult = graphResult.replaceRootNode(resource.id)
          newResource     = resource.copy(graph = rootGraphResult)
        } yield BlazegraphIndexingStreamEntry(newResource) -> false

      case resDeleteCandidate                                                                           => Task.pure(resDeleteCandidate)
    }.through(projection match {
      case p: ElasticSearchProjection => esProjectionPipeIndex(view, p)
      case p: SparqlProjection        => blazegraphProjectionPipeIndex(view, p)
    }).flatMap(Stream.chunk)
      // Persist progress in cache and in primary store
      .persistProgressWithCache(
        progress,
        pId,
        projections,
        cache.put(pId, _),
        cfg.projection,
        cfg.cache
      )
      .enableMetrics
      .map(_.value)
  }

  private def esProjectionPipeIndex(
      view: ViewIndex[CompositeView],
      projection: ElasticSearchProjection
  ): Pipe[Task, Chunk[Message[(BlazegraphIndexingStreamEntry, Boolean)]], Chunk[Message[Unit]]] =
    _.evalMapFilterValue { case (BlazegraphIndexingStreamEntry(resource), deleteCandidate) =>
      val esRes = CompositeIndexingStreamEntry(resource.discardSource)
      val index = idx(projection, view)
      if (deleteCandidate) esRes.delete(resource.id, index).map(Some.apply)
      else
        esRes.writeOrNone(
          index,
          projection.resourceSchemas,
          projection.resourceTypes,
          projection.includeMetadata,
          projection.includeDeprecated,
          sourceAsText = false,
          context = projection.context
        )
    }.runAsyncUnit { bulk =>
      // Pushes INDEX/DELETE Elasticsearch bulk operations
      IO.when(bulk.nonEmpty)(esClient.bulk(bulk))
    }.mapValue(_ => ())

  private def blazegraphProjectionPipeIndex(
      view: ViewIndex[CompositeView],
      projection: SparqlProjection
  ): Pipe[Task, Chunk[Message[(BlazegraphIndexingStreamEntry, Boolean)]], Chunk[Message[Unit]]] =
    _.evalMapFilterValue { case (res, deleteCandidate) =>
      if (deleteCandidate) res.delete().map(Some.apply)
      else
        res.writeOrNone(
          projection.resourceSchemas,
          projection.resourceTypes,
          projection.includeDeprecated,
          projection.includeMetadata
        )
    }.runAsyncUnit { bulk =>
      // Pushes DROP/REPLACE queries to Blazegraph
      IO.when(bulk.nonEmpty)(blazeClient.bulk(ns(projection, view), bulk))
    }.mapValue(_ => ())

  private def createIndices(view: ViewIndex[CompositeView]): Task[Unit] =
    for {
      _ <- blazeClient.createNamespace(view.index) // common blazegraph namespace
      _ <- Task.traverse(view.value.projections.value) {
             case p: ElasticSearchProjection =>
               esClient.createIndex(idx(p, view), Some(p.mapping), p.settings).void
             case p: SparqlProjection        =>
               blazeClient.createNamespace(ns(p, view)).void
           }
    } yield ()

  private def handleProgress(
      strategy: ProgressStrategy,
      view: ViewIndex[CompositeView]
  ): Task[Map[ProjectionId, ProjectionProgress[SkipIndexingUntil]]] = {

    def collect(list: List[ProjectionProgress[CompositeViewProjectionId]]) =
      list.foldLeft(Map.empty[ProjectionId, ProjectionProgress[SkipIndexingUntil]]) { case (acc, progress) =>
        val newProgress    = progress.as(noSkipIndexing)
        val sourceProgress = acc
          .get(progress.value.sourceId)
          .collect { case prev if newProgress.offset > prev.offset => prev }
          .getOrElse(newProgress)
        acc + (progress.value -> newProgress) + (progress.value.sourceId -> sourceProgress)
      }

    def reset(projectionId: CompositeViewProjectionId) =
      cache.remove(projectionId) >>
        cache.put(projectionId, NoProgress) >>
        projections.recordProgress(projectionId, NoProgress).as(NoProgress(projectionId))

    strategy match {
      case ProgressStrategy.Continue    =>
        IO.traverse(projectionIds(view))(pId =>
          for {
            progress <- projections.progress(pId)
            _        <- cache.put(pId, progress)
          } yield progress.as(pId)
        ).map(collect)
      case ProgressStrategy.FullRestart =>
        IO.traverse(projectionIds(view))(pId => reset(pId)).map(collect)
      case PartialRestart(toRestart)    =>
        IO.traverse(projectionIds(view)) {
          case pId if toRestart.contains(pId) =>
            projections.progress(pId).flatMap { progress =>
              reset(pId).map(reset => (reset, reset.map(_.sourceId -> SkipIndexingUntil(progress.offset))))
            }
          case pId                            =>
            projections.progress(pId).map { progress =>
              (progress.as(pId), progress.as(pId.sourceId -> noSkipIndexing))
            }
        }.map(_.foldLeft(Map.empty[ProjectionId, ProjectionProgress[SkipIndexingUntil]]) {
          case (acc, (pProgress, sProgress)) =>
            val (sId, skipIndexingUntil) = sProgress.value
            val sourceProgress           = acc
              .get(sId)
              .map {
                case prev if prev.offset >= sProgress.offset =>
                  sProgress.as(SkipIndexingUntil(prev.value.offset.max(skipIndexingUntil.offset)))
                case prev                                    =>
                  prev.as(SkipIndexingUntil(prev.value.offset.max(skipIndexingUntil.offset)))
              }
              .getOrElse(sProgress.as(skipIndexingUntil))
            acc + (pProgress.value -> pProgress.as(noSkipIndexing)) + (sId -> sourceProgress)
        })
      case _                            =>
        Task.raiseError(
          new IllegalArgumentException(
            "Only `Continue`, `FullRestart` and `PartialRestart` are valid progress strategies for composite views."
          )
        )
    }
  }

  private def idx(projection: ElasticSearchProjection, view: ViewIndex[CompositeView]): IndexLabel =
    CompositeViews.index(projection, view.value, view.rev, esConfig.prefix)

  private def ns(projection: SparqlProjection, view: ViewIndex[CompositeView]): String =
    CompositeViews.namespace(projection, view.value, view.rev, blazeConfig.prefix)

  private def indexingConfig(projection: CompositeViewProjection): ExternalIndexingConfig =
    projection match {
      case _: ElasticSearchProjection => esConfig
      case _: SparqlProjection        => blazeConfig
    }

  private def projectionIds(view: ViewIndex[CompositeView])                          =
    CompositeViews.projectionIds(view.value, view.rev).map { case (_, _, projectionId) => projectionId }

  private def streamRepeat[A](f: UIO[A], fixedRate: FiniteDuration): Stream[Task, A] =
    Stream.eval(f) ++ Stream.repeatEval(f).metered(fixedRate)

  def fetchProjectsCounts(view: CompositeView): UIO[Map[CompositeViewSource, ProjectCount]] = UIO
    .traverse(view.sources.value) {
      case source: ProjectSource       => projectsCounts.get(view.project).map(_.map(source -> _))
      case source: CrossProjectSource  => projectsCounts.get(source.project).map(_.map(source -> _))
      case source: RemoteProjectSource => remoteProjectsCounts(source).map(_.map(source -> _))
    }
    .map(_.flatten.toMap)

  private def isOnFirstIntervalRestart(view: ViewIndex[CompositeView], duration: FiniteDuration)(
      time: Instant
  ): Boolean = {
    val lowerBound = view.value.updatedAt.plusMillis(duration.toMillis - duration.toMillis / 2)
    val upperBound = view.value.updatedAt.plusMillis(duration.toMillis + duration.toMillis / 2)
    time.isAfter(lowerBound) && time.isBefore(upperBound)
  }

  private def triggerRestartProjections(
      view: ViewIndex[CompositeView],
      sources: Set[CompositeViewSource]
  ): UIO[Unit] = {
    val projectionsToRestart = for {
      source     <- sources
      projection <- view.value.projections.value
    } yield CompositeViews.projectionId(source, projection, view.rev)
    restartProjections(view.id, view.value.project, projectionsToRestart)
  }

  def replaceId(query: SparqlConstructQuery, iri: Iri): SparqlConstructQuery =
    SparqlConstructQuery.unsafe(query.value.replaceAll(quote(idTemplating), s"<$iri>"))

}

object CompositeIndexingStream {
  implicit private val logger: Logger = Logger[CompositeIndexingStream]

  private[indexing] type RemoteProjectsCounts = RemoteProjectSource => UIO[Option[ProjectCount]]
  private[indexing] type RestartProjections   = (Iri, ProjectRef, Set[CompositeViewProjectionId]) => UIO[Unit]

  def apply(
      config: CompositeViewsConfig,
      esClient: ElasticSearchClient,
      blazeClient: BlazegraphClient,
      deltaClient: DeltaClient,
      cache: ProgressesCache,
      projectsCounts: ProjectsCounts,
      indexingController: CompositeIndexingController,
      projections: Projection[Unit],
      indexingSource: IndexingSource,
      remoteIndexingSource: RemoteIndexingSource
  )(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler): CompositeIndexingStream = {
    val restartProjections: RestartProjections    = (id, project, projections) =>
      indexingController.restart(id, project, Restart(PartialRestart(projections)))
    val remoteProjectCounts: RemoteProjectsCounts = source =>
      deltaClient
        .projectCount(source)
        .redeem(
          err => {
            val msg =
              s"Error while retrieving the project count for the remote Delta instance '${source.endpoint}' on project '${source.project}'"
            logger.error(msg, err)
            None
          },
          Some(_)
        )
    new CompositeIndexingStream(
      config.elasticSearchIndexing,
      esClient,
      config.blazegraphIndexing,
      blazeClient,
      cache,
      projectsCounts,
      remoteProjectCounts,
      restartProjections,
      projections,
      indexingSource,
      remoteIndexingSource
    )
  }

  /**
    * Restarts from the offset [[akka.persistence.query.NoOffset]] for the passed ''projectionIds''. This restarts the
    * sources involved into those projections from [[akka.persistence.query.NoOffset]] as well, while avoiding
    * re-indexing triples into the common Blazegraph namespaces until the current source offset is reached
    */
  final case class PartialRestart(projectionIds: Set[CompositeViewProjectionId]) extends ProgressStrategy

  /**
    * Skips from indexing into the common Blazegraph namespace until the passed ''offset'' is reached
    *
    * @see
    *   [[PartialRestart(projectionIds)]]
    */
  final case class SkipIndexingUntil(offset: Offset)
  val noSkipIndexing: SkipIndexingUntil = SkipIndexingUntil(NoOffset)

}
