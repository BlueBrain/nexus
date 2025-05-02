package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, Refresh}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.MainIndexConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.MainIndexingCoordinator.mainIndexingPipeline
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.Start
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, Elem, ElemStream, ExecutionStrategy, Source}
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final class MainIndexingAction(sink: Sink, override val timeout: FiniteDuration)(implicit
    cr: RemoteContextResolution
) extends IndexingAction {

  override protected def kamonMetricComponent: KamonMetricComponent = KamonMetricComponent(
    "elasticsearch-main-indexing"
  )

  private def compile(project: ProjectRef, elem: Elem[GraphResource]) =
    CompiledProjection.compile(
      mainIndexingProjectionMetadata(project),
      ExecutionStrategy.TransientSingleNode,
      Source(_ => Stream(elem)),
      mainIndexingPipeline,
      sink
    )

  override def projections(project: ProjectRef, elem: Elem[GraphResource]): ElemStream[CompiledProjection] = {
    // TODO: get rid of elem here
    val entityType = EntityType("main-indexing")
    Stream.fromEither[IO](
      compile(project, elem).map { projection =>
        SuccessElem(entityType, mainIndexingId, project, Instant.EPOCH, Start, projection, 1)
      }
    )
  }
}

object MainIndexingAction {
  def apply(
      client: ElasticSearchClient,
      config: MainIndexConfig,
      timeout: FiniteDuration,
      syncIndexingRefresh: Refresh
  )(implicit cr: RemoteContextResolution): MainIndexingAction = {
    val batchConfig = BatchConfig.individual
    new MainIndexingAction(
      ElasticSearchSink
        .mainIndexing(client, batchConfig.maxElements, batchConfig.maxInterval, config.index, syncIndexingRefresh),
      timeout
    )
  }
}
