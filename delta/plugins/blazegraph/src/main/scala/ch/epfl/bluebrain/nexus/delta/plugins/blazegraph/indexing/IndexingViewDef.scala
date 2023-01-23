package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.data.NonEmptyChain
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewState
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import monix.bio.Task

/**
  * Definition of a Blazegraph view to build a projection
  */
sealed trait IndexingViewDef extends Product with Serializable {

  def ref: ViewRef

}

object IndexingViewDef {

  private val logger: Logger = Logger[IndexingViewDef]

  /**
    * Active view eligible to be run as a projection by the supervisor
    */
  final case class ActiveViewDef(
      ref: ViewRef,
      projection: String,
      resourceTag: Option[UserTag],
      pipeChain: Option[PipeChain],
      namespace: String
  ) extends IndexingViewDef

  /**
    * Deprecated view to be cleaned up and removed from the supervisor
    */
  final case class DeprecatedViewDef(ref: ViewRef) extends IndexingViewDef

  def apply(
      state: BlazegraphViewState,
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
          BlazegraphViews.projectionName(state),
          indexing.resourceTag,
          indexing.pipeChain,
          BlazegraphViews.namespace(state.uuid, state.rev, prefix)
        )
    }

  def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      elems: ElemStream[GraphResource],
      sink: Sink
  ): Task[CompiledProjection] =
    compile(v, compilePipeChain, _ => elems, sink)

  def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      graphStream: GraphResourceStream,
      sink: Sink
  ): Task[CompiledProjection] =
    compile(v, compilePipeChain, graphStream.continuous(v.ref.project, v.resourceTag.getOrElse(Tag.latest), _), sink)

  private def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      stream: Offset => ElemStream[GraphResource],
      sink: Sink
  ): Task[CompiledProjection] = {
    val project  = v.ref.project
    val id       = v.ref.viewId
    val metadata = ProjectionMetadata(
      BlazegraphViews.entityType.value,
      v.projection,
      Some(project),
      Some(id)
    )

    val postPipes: Operation = GraphResourceToNTriples

    val compiled = for {
      pipes      <- v.pipeChain.traverse(compilePipeChain)
      chain       = pipes.fold(NonEmptyChain.one(postPipes))(NonEmptyChain(_, postPipes))
      projection <- CompiledProjection.compile(
                      metadata,
                      ExecutionStrategy.PersistentSingleNode,
                      Source(stream),
                      chain,
                      sink
                    )
    } yield projection

    Task.fromEither(compiled).tapError { e =>
      Task.delay(logger.error(s"View '$project/$id' could not be compiled.", e))
    }
  }
}
