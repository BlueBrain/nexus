package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.Monoid
import cats.data.NonEmptyChain
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.GraphResourceToNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Interval, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewProjection, CompositeViewSource, CompositeViewState, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.GraphResourceToDocument
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import fs2.Stream
import monix.bio.{Task, UIO}

/**
  * Definition of a composite view
  */
sealed trait CompositeViewDef extends Product with Serializable {

  def ref: ViewRef

  override def toString: String = s"${ref.project}/${ref.viewId}"

}

object CompositeViewDef {

  /**
    * Active view eligible to be run as a projection by the supervisor
    */
  final case class ActiveViewDef(ref: ViewRef, rev: Int, value: CompositeViewValue) extends CompositeViewDef

  /**
    * Deprecated view to be cleaned up and removed from the supervisor
    */
  final case class DeprecatedViewDef(ref: ViewRef) extends CompositeViewDef

  def apply(state: CompositeViewState): CompositeViewDef =
    if (state.deprecated)
      DeprecatedViewDef(
        ViewRef(state.project, state.id)
      )
    else
      ActiveViewDef(
        ViewRef(state.project, state.id),
        state.rev,
        state.value
      )

  /**
    * Compile the composite views into a unique stream
    * @param view
    *   the view
    * @param fetchProgress
    *   how to fetch the progress
    * @param compileSource
    *   how to compile a composite view source
    * @param compileTarget
    *   how to compile a composite view projection
    * @param rebuild
    *   the rebuild method to apply to the rebuild branches
    * @param closeBBranch
    *   the operation to apply at the end of the branches
    */
  def compile(
      view: ActiveViewDef,
      fetchProgress: UIO[CompositeProgress],
      compileSource: CompositeViewSource => Task[(Iri, Source, Source, Operation)],
      compileTarget: CompositeViewProjection => Task[(Iri, Operation)],
      rebuild: ElemStream[Unit] => ElemStream[Unit],
      closeBBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): Task[ElemStream[Unit]] = {
    implicit val monoidInstance: Monoid[ElemStream[Unit]] =
      new Monoid[ElemStream[Unit]] {
        def empty: ElemStream[Unit]                                             = Stream.empty
        def combine(x: ElemStream[Unit], y: ElemStream[Unit]): ElemStream[Unit] = x.merge(y)
      }

    val sources = NonEmptyChain.fromNonEmptyList(view.value.sources.toNonEmptyList)
    val targets = NonEmptyChain.fromNonEmptyList(view.value.projections.toNonEmptyList)

    for {
      compiledSources  <- sources.traverse(compileSource)
      targetOperations <- targets.traverse(compileTarget)
      // Main branches
      mains             = compiledSources.reduceMap { case (sourceId, sourceMain, _, sourceOperation) =>
                            compileMain(sourceId, sourceMain, sourceOperation, targetOperations, fetchProgress, closeBBranch)
                          }
      // Rebuild branches
      rebuilds          = rebuild(
                            compiledSources
                              .reduceMap { case (sourceId, _, sourceRebuild, _) =>
                                compileRebuild(sourceId, sourceRebuild, targetOperations, fetchProgress, closeBBranch)
                              }
                          )
    } yield mains |+| rebuilds
  }

  /**
    * If a rebuild strategy is defined, prepend the provided stream by a cooling time and waits for a trigger, repeating
    * the whole stream indefinitely.
    *
    * @param stream
    *   the stream to rebuild
    * @param rebuildStrategy
    *   the strategy
    * @param rebuildWhen
    *   the stream which
    */
  def rebuild[A](
      stream: Stream[Task, A],
      rebuildStrategy: Option[RebuildStrategy],
      rebuildWhen: Stream[Task, Boolean]
  ): Stream[Task, A] =
    rebuildStrategy match {
      case Some(Interval(value)) =>
        val waitingForRebuild = Stream.never[Task].interruptWhen(rebuildWhen).drain
        val sleep             = Stream.sleep[Task](value).drain
        (waitingForRebuild ++ sleep ++ stream).repeat
      case None                  =>
        // No rebuild strategy has been defined
        Stream.empty[Task]
    }

  /**
    * Compile the main branch for a given source
    * @param sourceId
    *   the id of the composite view source
    * @param source
    *   the source providing the elements
    * @param sourceOperation
    *   the operation to apply to the source
    * @param targets
    *   the operations for each target
    * @param fetchProgress
    *   how to fetch the progress
    * @param closeBranch
    *   the operation to apply at the end of a branch
    */
  private def compileMain(
      sourceId: Iri,
      source: Source,
      sourceOperation: Operation,
      targets: NonEmptyChain[(Iri, Operation)],
      fetchProgress: UIO[CompositeProgress],
      closeBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): ElemStream[Unit] =
    Stream.eval(fetchProgress).flatMap { progress =>
      val sourceOffset = progress.sources.get(sourceId)
      val main         = for {
        leapedSource <- sourceOffset.map(sourceOperation.leapUntil).getOrElse(Right(sourceOperation))
        mainTargets  <- targets.traverse { case (id, operation) =>
                          targetOperation(
                            progress,
                            CompositeBranch.rebuild(sourceId, id),
                            operation,
                            closeBranch
                          )
                        }
        result       <- source.through(leapedSource).flatMap(_.broadcastThrough(mainTargets))
      } yield result
      main match {
        case Left(e)       => Stream.raiseError[Task](e)
        case Right(source) => source.apply(sourceOffset.getOrElse(Offset.start))
      }
    }

  /**
    * Compile the rebuild branch for a given source
    * @param sourceId
    *   the id of the composite view source
    * @param source
    *   the source providing the elements
    * @param targets
    *   the operations for each target
    * @param fetchProgress
    *   how to fetch the progress
    * @param closeBranch
    *   the operation to apply at the end of a branch
    */
  private def compileRebuild(
      sourceId: Iri,
      source: Source,
      targets: NonEmptyChain[(Iri, Operation)],
      fetchProgress: UIO[CompositeProgress],
      closeBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): ElemStream[Unit] =
    Stream.eval(fetchProgress).flatMap { progress =>
      val sourceOffset = progress.sources.get(sourceId)
      val rebuild      = for {
        rebuildTargets <- targets.traverse { case (id, operation) =>
                            targetOperation(
                              progress,
                              CompositeBranch.rebuild(sourceId, id),
                              operation,
                              closeBranch
                            )
                          }
        result         <- source.broadcastThrough(rebuildTargets)
      } yield result
      rebuild match {
        case Left(e)       => Stream.raiseError[Task](e)
        case Right(source) => source.apply(sourceOffset.getOrElse(Offset.start))
      }
    }

  /**
    * Complete and compiles the operation for the branch applying a leap depending on the current offset and the final
    * operation
    *
    * @param progress
    *   the composite progress
    * @param branch
    *   the current branch
    * @param operation
    *   the current operation for this target
    * @param closeBranch
    *   the final operation to apply to this branch
    */
  private def targetOperation(
      progress: CompositeProgress,
      branch: CompositeBranch,
      operation: Operation,
      closeBranch: (CompositeBranch, ProjectionProgress) => Operation
  ) = {
    val branchProgress = progress.branches.get(branch)
    branchProgress
      .map { p => operation.leapUntil(p.offset) }
      .getOrElse(Right(operation))
      .flatMap(
        Operation
          .merge(_, closeBranch(branch, branchProgress.getOrElse(ProjectionProgress.NoProgress)))
      )
  }

  /**
    * Compiles a composite view source into the main and rebuild sources and the operation to apply after it
    * @param source
    *   the composite view source
    * @param compilePipeChain
    *   how to compile the pipe chain of the composite view source
    * @param toElemStream
    *   generates the element stream for the source in the context of a branch
    * @param sink
    *   the intermediate sink
    */
  def compileSource(
      source: CompositeViewSource,
      compilePipeChain: PipeChain.Compile,
      toElemStream: (CompositeViewSource, CompositeBranch.Run) => Source,
      sink: Sink
  ): Task[(Iri, Source, Source, Operation)] = Task.fromEither {
    for {
      pipes        <- source.pipeChain.traverse(compilePipeChain)
      // We apply `Operation.observe` as we want to keep the GraphResource for the rest of the stream
      tail         <- Operation.merge(GraphResourceToNTriples, sink).map(_.observe)
      chain         = pipes.fold(NonEmptyChain.one(tail))(NonEmptyChain(_, tail))
      operation    <- Operation.merge(chain)
      // We create the elem stream for the two types of branch
      mainSource    = toElemStream(source, CompositeBranch.Run.Main)
      rebuildSource = toElemStream(source, CompositeBranch.Run.Rebuild)
    } yield (source.id, mainSource, rebuildSource, operation)
  }

  /**
    * Compiles a composite projection into an operation
    * @param target
    *   the composite view projection
    * @param compilePipeChain
    *   how to compile the pipe chain of the composite view projection
    * @param queryPipe
    *   how to instantiate the query pipe at the beginning of the projection
    * @param targetSink
    *   how to instantiate the target sink
    * @param cr
    *   the remote context resolution for ES projections
    */
  def compileTarget(
      target: CompositeViewProjection,
      compilePipeChain: PipeChain.Compile,
      queryPipe: SparqlConstructQuery => Operation,
      targetSink: CompositeViewProjection => Sink
  )(implicit cr: RemoteContextResolution): Task[(Iri, Operation)] = Task.fromEither {
    val query = queryPipe(target.query)
    val sink  = targetSink(target)

    target match {
      case e: ElasticSearchProjection => compileElasticsearch(e, compilePipeChain, query, sink)
      case s: SparqlProjection        => compileSparql(s, compilePipeChain, query, sink)
    }
  }

  // Compiling an Elasticsearch projection of a composite view
  private def compileElasticsearch(
      elasticsearch: ElasticSearchProjection,
      compilePipeChain: PipeChain.Compile,
      query: Operation,
      sink: Sink
  )(implicit cr: RemoteContextResolution) = {

    val head = query
    // Transforming to json and push to the sink
    val tail = NonEmptyChain(new GraphResourceToDocument(elasticsearch.context, elasticsearch.includeContext), sink)

    for {
      pipes  <- elasticsearch.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(NonEmptyChain.one(head))(NonEmptyChain(head, _)) ++ tail
      result <- Operation.merge(chain)
    } yield elasticsearch.id -> result
  }

  // Compiling an Elasticsearch projection of a composite view
  private def compileSparql(
      sparql: SparqlProjection,
      compilePipeChain: PipeChain.Compile,
      query: Operation,
      sink: Sink
  ) = {

    val head = query
    // Transforming to n-triples and push to the sink
    val tail = NonEmptyChain(GraphResourceToNTriples, sink)

    for {
      pipes  <- sparql.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(NonEmptyChain.one(head))(NonEmptyChain(head, _)) ++ tail
      result <- Operation.merge(chain)
    } yield sparql.id -> result
  }

}
