package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.Refresh
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.DefaultIndexConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.DefaultIndexingCoordinator.defaultIndexingPipeline
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, Elem, ExecutionStrategy, Source}
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final class DefaultIndexingAction(sink: Sink, override val timeout: FiniteDuration)(implicit
    cr: RemoteContextResolution
) extends IndexingAction {

  private def compile(project: ProjectRef, elem: Elem[GraphResource]) =
    CompiledProjection.compile(
      defaultIndexingProjectionMetadata(project),
      ExecutionStrategy.TransientSingleNode,
      Source(_ => Stream(elem)),
      defaultIndexingPipeline,
      sink
    )

  override def projections(project: ProjectRef, elem: Elem[GraphResource]): ElemStream[CompiledProjection] = {
    // TODO: get rid of elem here
    val entityType = EntityType("default-indexing")
    Stream.fromEither[IO](
      compile(project, elem).map { projection =>
        SuccessElem(entityType, defaultIndexingId, project, Instant.EPOCH, Start, projection, 1)
      }
    )
  }
}

object DefaultIndexingAction {
  def apply(
      client: ElasticSearchClient,
      config: DefaultIndexConfig,
      timeout: FiniteDuration,
      syncIndexingRefresh: Refresh
  )(implicit cr: RemoteContextResolution): DefaultIndexingAction = {
    val batchConfig = BatchConfig.individual
    new DefaultIndexingAction(
      ElasticSearchSink
        .defaultIndexing(client, batchConfig.maxElements, batchConfig.maxInterval, config.index, syncIndexingRefresh),
      timeout
    )
  }
}
