package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphSink, IndexingViewDef}
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
  * To synchronously index a resource in the different Blazegraph views of a project
  * @param fetchCurrentViews
  *   get the views of the projects in a finite stream
  * @param compilePipeChain
  *   to compile the views
  * @param sink
  *   the Blazegraph sink
  * @param timeout
  *   a maximum duration for the indexing
  */
final class BlazegraphIndexingAction(
    fetchCurrentViews: ProjectRef => ElemStream[IndexingViewDef],
    compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
    sink: ActiveViewDef => Sink,
    override val timeout: FiniteDuration
) extends IndexingAction {

  private def compile(view: IndexingViewDef, elem: Elem[GraphResource]): Task[Option[CompiledProjection]] = view match {
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

object BlazegraphIndexingAction {

  def apply(
      views: BlazegraphViews,
      registry: ReferenceRegistry,
      client: BlazegraphClient,
      timeout: FiniteDuration
  ): BlazegraphIndexingAction = {
    val batchConfig = BatchConfig.individual
    new BlazegraphIndexingAction(
      views.currentIndexingViews,
      PipeChain.compile(_, registry),
      (v: ActiveViewDef) => new BlazegraphSink(client, batchConfig.maxElements, batchConfig.maxInterval, v.namespace),
      timeout
    )
  }

}
