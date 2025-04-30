package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.main.MainIndexDef
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DefaultLabelPredicates, SourceAsText}

sealed trait MainIndexingCoordinator

object MainIndexingCoordinator {

  private[indexing] case class ProjectDef(ref: ProjectRef, markedForDeletion: Boolean)

  private val logger = Logger[MainIndexingCoordinator]

  /** If indexing is disabled we can only log */
  final private case object Noop extends MainIndexingCoordinator {
    def log: IO[Unit] =
      logger.info("Main indexing has been disabled via config")

  }

  /**
    * Coordinates the main indexing as a projection per project
    *
    * @param fetchProjects
    *   stream of projects
    * @param graphStream
    *   to provide the data feeding the projections
    * @param supervisor
    *   the general supervisor
    */
  final private class Active(
      fetchProjects: Offset => ElemStream[ProjectDef],
      graphStream: GraphResourceStream,
      supervisor: Supervisor,
      sink: Sink,
      createAlias: ProjectRef => IO[Unit]
  )(implicit cr: RemoteContextResolution)
      extends MainIndexingCoordinator {

    def run(offset: Offset): ElemStream[Unit] =
      fetchProjects(offset).evalMap {
        _.traverse {
          case p if p.markedForDeletion => destroy(p.ref)
          case p                        => start(p.ref)
        }
      }

    private def compile(project: ProjectRef): IO[CompiledProjection] =
      IO.fromEither(
        CompiledProjection.compile(
          mainIndexingProjectionMetadata(project),
          ExecutionStrategy.PersistentSingleNode,
          Source(graphStream.continuous(project, SelectFilter.latest, _)),
          mainIndexingPipeline,
          sink
        )
      )

    // Start the main indexing projection for the given project
    private def start(project: ProjectRef): IO[Unit] =
      for {
        compiled <- compile(project)
        _        <- createAlias(project)
        status   <- supervisor.describe(compiled.metadata.name)
        _        <- status match {
                      case Some(value) if value.status == ExecutionStatus.Running =>
                        logger.info(s"Main indexing of '$project' is already running.")
                      case _                                                      =>
                        logger.info(s"Starting main indexing of '$project'...") >>
                          supervisor.run(compiled)
                    }
      } yield ()

    // Destroy the indexing process for the given project
    private def destroy(project: ProjectRef): IO[Unit] = {
      logger.info(s"Project '$project' has been marked as deleted, stopping the main indexing...") >>
        supervisor
          .destroy(mainIndexingProjection(project))
          .void
    }
  }

  def mainIndexingPipeline(implicit cr: RemoteContextResolution): NonEmptyChain[Operation] =
    NonEmptyChain(
      DefaultLabelPredicates.withConfig(()),
      SourceAsText.withConfig(()),
      new GraphResourceToDocument(defaultIndexingContext, false)
    )

  val metadata: ProjectionMetadata = ProjectionMetadata("system", "main-indexing-coordinator", None, None)

  def apply(
      projects: Projects,
      graphStream: GraphResourceStream,
      supervisor: Supervisor,
      client: ElasticSearchClient,
      mainIndex: MainIndexDef,
      batch: BatchConfig,
      indexingEnabled: Boolean
  )(implicit cr: RemoteContextResolution): IO[MainIndexingCoordinator] =
    if (indexingEnabled) {
      val targetIndex = mainIndex.name

      def fetchProjects(offset: Offset) =
        projects.states(offset).map(_.map { p => ProjectDef(p.project, p.markedForDeletion) })

      def elasticsearchSink =
        ElasticSearchSink.mainIndexing(client, batch.maxElements, batch.maxInterval, targetIndex, Refresh.False)

      def createAlias(project: ProjectRef) = client.createAlias(mainIndexingAlias(targetIndex, project))

      client.createIndex(targetIndex, Some(mainIndex.mapping), Some(mainIndex.settings)) >>
        apply(fetchProjects, graphStream, supervisor, elasticsearchSink, createAlias)
    } else {
      Noop.log.as(Noop)
    }

  def apply(
      fetchProjects: Offset => ElemStream[ProjectDef],
      graphStream: GraphResourceStream,
      supervisor: Supervisor,
      sink: Sink,
      createAlias: ProjectRef => IO[Unit]
  )(implicit cr: RemoteContextResolution): IO[MainIndexingCoordinator] = {
    val coordinator = new Active(fetchProjects, graphStream, supervisor, sink, createAlias)
    val compiled    =
      CompiledProjection.fromStream(metadata, ExecutionStrategy.EveryNode, offset => coordinator.run(offset))
    supervisor.run(compiled).as(coordinator)
  }
}
