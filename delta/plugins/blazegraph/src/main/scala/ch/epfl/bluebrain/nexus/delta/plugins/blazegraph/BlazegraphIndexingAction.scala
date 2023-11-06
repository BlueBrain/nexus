package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.{ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.{BlazegraphSink, IndexingViewDef}
import ch.epfl.bluebrain.nexus.delta.sdk.IndexingAction
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sourcing.config.BatchConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream

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
)(implicit timer: Timer[IO], cs: ContextShift[IO])
    extends IndexingAction {

  private def compile(view: IndexingViewDef, elem: Elem[GraphResource])(implicit
      timer: Timer[IO],
      cs: ContextShift[IO]
  ): IO[Option[CompiledProjection]] = view match {
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

object BlazegraphIndexingAction {

  def apply(
      views: BlazegraphViews,
      registry: ReferenceRegistry,
      client: BlazegraphClient,
      timeout: FiniteDuration
  )(implicit baseUri: BaseUri, timer: Timer[IO], cs: ContextShift[IO]): BlazegraphIndexingAction = {
    val batchConfig = BatchConfig.individual
    new BlazegraphIndexingAction(
      views.currentIndexingViews,
      PipeChain.compile(_, registry),
      (v: ActiveViewDef) => new BlazegraphSink(client, batchConfig.maxElements, batchConfig.maxInterval, v.namespace),
      timeout
    )
  }

}
