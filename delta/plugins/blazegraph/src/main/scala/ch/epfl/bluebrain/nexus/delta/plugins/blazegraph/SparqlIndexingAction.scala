package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{IndexingViewDef, SparqlSink}
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, SuccessElemStream, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*
import fs2.Stream

import scala.concurrent.duration.FiniteDuration

/**
  * To synchronously index a resource in the different SPARQL views of a project
  * @param fetchCurrentViews
  *   get the views of the projects in a finite stream
  * @param compilePipeChain
  *   to compile the views
  * @param sink
  *   the SPARQL sink
  * @param timeout
  *   a maximum duration for the indexing
  */
final class SparqlIndexingAction(
    fetchCurrentViews: ProjectRef => SuccessElemStream[IndexingViewDef],
    compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
    sink: ActiveViewDef => Sink,
    override val timeout: FiniteDuration
) extends IndexingAction {

  override protected def kamonMetricComponent: KamonMetricComponent = KamonMetricComponent("blazegraph-indexing")

  private def compile(view: IndexingViewDef, elem: Elem[GraphResource]): IO[Option[CompiledProjection]] = view match {
    // Synchronous indexing only applies to views that index the latest version
    case active: ActiveViewDef if active.selectFilter.tag == Tag.Latest =>
      IndexingViewDef
        .compile(
          active,
          compilePipeChain,
          Stream(elem),
          sink(active)
        )
        .map(Some(_))
    case _: ActiveViewDef                                               => IO.none
    case _: DeprecatedViewDef                                           => IO.none
  }

  override def projections(project: ProjectRef, elem: Elem[GraphResource]): ElemStream[CompiledProjection] =
    fetchCurrentViews(project).evalMap { _.evalMapFilter(compile(_, elem)) }
}

object SparqlIndexingAction {

  def apply(
      views: BlazegraphViews,
      registry: ReferenceRegistry,
      client: SparqlClient,
      timeout: FiniteDuration
  )(implicit baseUri: BaseUri): SparqlIndexingAction = {
    val batchConfig = BatchConfig.individual
    new SparqlIndexingAction(
      views.currentIndexingViews,
      PipeChain.compile(_, registry),
      (v: ActiveViewDef) => new SparqlSink(client, batchConfig.maxElements, batchConfig.maxInterval, v.namespace),
      timeout
    )
  }

}
