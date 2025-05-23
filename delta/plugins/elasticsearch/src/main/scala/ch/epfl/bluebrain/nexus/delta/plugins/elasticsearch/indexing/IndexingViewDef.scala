package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.NonEmptyChain
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.views.DefaultIndexDef
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.{IndexingRev, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.SelectFilter
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.*
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import io.circe.JsonObject

/**
  * Definition of a view to build a projection
  */
sealed trait IndexingViewDef extends Product with Serializable {

  def ref: ViewRef

  override def toString: String = s"${ref.project}/${ref.viewId}"

}

object IndexingViewDef {

  private val logger = Logger[IndexingViewDef]

  /**
    * Active view eligible to be run as a projection by the supervisor
    */
  final case class ActiveViewDef(
      ref: ViewRef,
      projection: String,
      pipeChain: Option[PipeChain],
      selectFilter: SelectFilter,
      index: IndexLabel,
      mapping: JsonObject,
      settings: JsonObject,
      context: Option[ContextObject],
      indexingRev: IndexingRev,
      rev: Int
  ) extends IndexingViewDef {

    def projectionMetadata: ProjectionMetadata =
      ProjectionMetadata(
        ElasticSearchViews.entityType.value,
        projection,
        Some(ref.project),
        Some(ref.viewId)
      )
  }

  /**
    * Deprecated view to be cleaned up and removed from the supervisor
    */
  final case class DeprecatedViewDef(ref: ViewRef) extends IndexingViewDef

  def apply(
      state: ElasticSearchViewState,
      defaultDefinition: DefaultIndexDef,
      prefix: String
  ): Option[IndexingViewDef] =
    state.value.asIndexingValue.map { indexing =>
      if (state.deprecated)
        DeprecatedViewDef(
          ViewRef(state.project, state.id)
        )
      else
        ActiveViewDef(
          ViewRef(state.project, state.id),
          ElasticSearchViews.projectionName(state),
          indexing.pipeChain,
          indexing.selectFilter,
          ElasticSearchViews.index(state.uuid, state.indexingRev, prefix),
          indexing.mapping.getOrElse(defaultDefinition.mapping),
          indexing.settings.getOrElse(defaultDefinition.settings),
          indexing.context,
          state.indexingRev,
          state.rev
        )

    }

  def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      elems: ElemStream[GraphResource],
      sink: Sink
  )(implicit cr: RemoteContextResolution): IO[CompiledProjection] =
    compile(v, compilePipeChain, _ => elems, sink)

  def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      graphStream: GraphResourceStream,
      sink: Sink
  )(implicit cr: RemoteContextResolution): IO[CompiledProjection] =
    compile(v, compilePipeChain, graphStream.continuous(v.ref.project, v.selectFilter, _), sink)

  private def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      stream: Offset => ElemStream[GraphResource],
      sink: Sink
  )(implicit cr: RemoteContextResolution): IO[CompiledProjection] = {

    val mergedContext        = v.context.fold(defaultIndexingContext) { defaultIndexingContext.merge(_) }
    val postPipes: Operation = new GraphResourceToDocument(mergedContext, false)

    val compiled = for {
      pipes      <- v.pipeChain.traverse(compilePipeChain)
      chain       = pipes.fold(NonEmptyChain.one(postPipes))(NonEmptyChain(_, postPipes))
      projection <- CompiledProjection.compile(
                      v.projectionMetadata,
                      ExecutionStrategy.PersistentSingleNode,
                      Source(stream),
                      chain,
                      sink
                    )
    } yield projection

    IO.fromEither(compiled).onError { case e =>
      logger.error(e)(s"View '${v.ref}' could not be compiled.")
    }
  }
}
