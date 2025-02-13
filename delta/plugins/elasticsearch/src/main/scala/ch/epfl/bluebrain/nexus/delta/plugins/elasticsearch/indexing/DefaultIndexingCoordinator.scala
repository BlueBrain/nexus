package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{DefaultMapping, DefaultSettings}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.pipes.{DefaultLabelPredicates, SourceAsText}

sealed trait DefaultIndexingCoordinator

object DefaultIndexingCoordinator {

  private[indexing] case class ProjectDef(ref: ProjectRef, markedForDeletion: Boolean)

  private val logger = Logger[DefaultIndexingCoordinator]

  /** If indexing is disabled we can only log */
  final private case object Noop extends DefaultIndexingCoordinator {
    def log: IO[Unit] =
      logger.info("Default indexing has been disabled via config")

  }

  /**
    * Coordinates the lifecycle of Elasticsearch views as projections
    *
    * @param fetchProjects
    *   stream of projects
    * @param graphStream
    *   to provide the data feeding the Elasticsearch projections
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
      extends DefaultIndexingCoordinator {

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
          defaultIndexingProjectionMetadata(project),
          ExecutionStrategy.PersistentSingleNode,
          Source(graphStream.continuous(project, SelectFilter.latest, _)),
          defaultIndexingPipeline,
          sink
        )
      )

    // Start the default indexing projection for the given project
    private def start(project: ProjectRef): IO[Unit] =
      for {
        compiled <- compile(project)
        _        <- createAlias(project)
        status   <- supervisor.describe(compiled.metadata.name)
        _        <- status match {
                      case Some(value) if value.status == ExecutionStatus.Running =>
                        logger.info(s"Default indexing of '$project' is already running.")
                      case _                                                      =>
                        logger.info(s"Starting default indexing of '$project'...") >>
                          supervisor.run(compiled)
                    }
      } yield ()

    // Destroy the indexing process for the given project
    private def destroy(project: ProjectRef): IO[Unit] = {
      logger.info(s"Project '$project' has been marked as deleted, stopping the default indexing...") >>
        supervisor
          .destroy(defaultIndexingProjection(project))
          .void
    }
  }

  def defaultIndexingPipeline(implicit cr: RemoteContextResolution): NonEmptyChain[Operation] =
    NonEmptyChain(
      DefaultLabelPredicates.withConfig(()),
      SourceAsText.withConfig(()),
      new GraphResourceToDocument(defaultIndexingContext, false)
    )

  val metadata: ProjectionMetadata = ProjectionMetadata("system", "default-indexing-coordinator", None, None)

  def apply(
      projects: Projects,
      graphStream: GraphResourceStream,
      supervisor: Supervisor,
      client: ElasticSearchClient,
      defaultMapping: DefaultMapping,
      defaultSettings: DefaultSettings,
      config: ElasticSearchViewsConfig
  )(implicit baseUri: BaseUri, cr: RemoteContextResolution): IO[DefaultIndexingCoordinator] =
    if (config.indexingEnabled) {
      val batch       = config.batch
      val targetIndex = config.defaultIndex.index

      def fetchProjects(offset: Offset) =
        projects.states(offset).map(_.map { p => ProjectDef(p.project, p.markedForDeletion) })

      def elasticsearchSink =
        ElasticSearchSink.defaultIndexing(client, batch.maxElements, batch.maxInterval, targetIndex, Refresh.False)

      def createAlias(project: ProjectRef) = client.createAlias(indexingAlias(config.defaultIndex, project))

      client.createIndex(targetIndex, Some(defaultMapping.value), Some(defaultSettings.value)) >>
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
  )(implicit cr: RemoteContextResolution): IO[DefaultIndexingCoordinator] = {
    val coordinator = new Active(fetchProjects, graphStream, supervisor, sink, createAlias)
    val compiled    =
      CompiledProjection.fromStream(metadata, ExecutionStrategy.EveryNode, offset => coordinator.run(offset))
    supervisor.run(compiled).as(coordinator)
  }
}
