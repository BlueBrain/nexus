package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.{ElasticSearchSink, IndexingViewDef}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream
import monix.bio.{Task, UIO}

import scala.concurrent.duration.FiniteDuration

/**
  * To synchronously index a resource in the different Elasticsearch views of a project
  * @param fetchCurrentViews
  *   get the views of the projects in a finite stream
  * @param compilePipeChain
  *   to compile the views
  * @param sink
  *   the Elasticsearch sink
  * @param timeout
  *   a maximum duration for the indexing
  */
final class ElasticSearchIndexingAction(
    fetchCurrentViews: ProjectRef => ElemStream[IndexingViewDef],
    compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
    sink: ActiveViewDef => Sink,
    override val timeout: FiniteDuration
) extends IndexingAction {

  private def compile(view: IndexingViewDef, elem: Elem[GraphResource])(implicit
      cr: RemoteContextResolution
  ): Task[Option[CompiledProjection]] = view match {
    // Synchronous indexing only applies to views that index the latest version
    case active: ActiveViewDef if active.resourceTag.isEmpty =>
      IndexingViewDef
        .compile(
          active,
          compilePipeChain,
          Stream(elem),
          sink(active)
        )
        .map(Some(_))
    case _: ActiveViewDef                                    => UIO.none
    case _: DeprecatedViewDef                                => UIO.none
  }

  def projections(project: ProjectRef, elem: Elem[GraphResource])(implicit
      cr: RemoteContextResolution
  ): ElemStream[CompiledProjection] =
    fetchCurrentViews(project).evalMap { _.evalMapFilter(compile(_, elem)) }
}
object ElasticSearchIndexingAction {

  def apply(
      views: ElasticSearchViews,
      registry: ReferenceRegistry,
      client: ElasticSearchClient,
      timeout: FiniteDuration,
      syncIndexingRefresh: Refresh
  ): ElasticSearchIndexingAction = {
    val batchConfig = BatchConfig.individual
    new ElasticSearchIndexingAction(
      views.currentIndexingViews,
      PipeChain.compile(_, registry),
      (v: ActiveViewDef) =>
        new ElasticSearchSink(client, batchConfig.maxElements, batchConfig.maxInterval, v.index, syncIndexingRefresh),
      timeout
    )
  }
}
