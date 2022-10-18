package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.data.NonEmptyChain
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewState
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.stream.GraphResourceStream
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import io.circe.JsonObject
import monix.bio.Task

/**
  * Definition of a view to build a projection
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
      index: IndexLabel,
      mapping: JsonObject,
      settings: JsonObject,
      context: Option[ContextObject]
  ) extends IndexingViewDef

  /**
    * Deprecated view to be cleaned up and removed from the supervisor
    */
  final case class DeprecatedViewDef(ref: ViewRef) extends IndexingViewDef

  def apply(
      state: ElasticSearchViewState,
      defaultMapping: JsonObject,
      defaultSettings: JsonObject,
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
          indexing.resourceTag,
          indexing.pipeChain,
          ElasticSearchViews.index(state.uuid, state.rev, prefix),
          indexing.mapping.getOrElse(defaultMapping),
          indexing.settings.getOrElse(defaultSettings),
          indexing.context
        )

    }

  def compile(
      v: ActiveViewDef,
      compilePipeChain: PipeChain => Either[ProjectionErr, Operation],
      graphStream: GraphResourceStream,
      sink: Sink
  )(implicit cr: RemoteContextResolution): Task[CompiledProjection] = {
    val project  = v.ref.project
    val id       = v.ref.viewId
    val metadata = ProjectionMetadata(
      ElasticSearchViews.entityType.value,
      v.projection,
      Some(project),
      Some(id)
    )

    val postPipes: Operation = new GraphResourceToDocument(v.context)

    val compiled = for {
      pipes      <- v.pipeChain.traverse(compilePipeChain)
      chain       = pipes.fold(NonEmptyChain.one(postPipes))(NonEmptyChain(_, postPipes))
      projection <- CompiledProjection.compile(
                      metadata,
                      ExecutionStrategy.PersistentSingleNode,
                      Source(graphStream(project, v.resourceTag.getOrElse(Tag.latest), _)),
                      chain,
                      sink
                    )
    } yield projection

    Task.fromEither(compiled).tapError { e =>
      Task.delay(logger.error(s"View '$project/$id' could not be compiled.", e))
    }
  }
}
