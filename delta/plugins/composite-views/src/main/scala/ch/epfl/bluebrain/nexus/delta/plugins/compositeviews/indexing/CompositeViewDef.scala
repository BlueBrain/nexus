package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.data.NonEmptyChain
import cats.effect.ExitCase
import cats.effect.ExitCase.{Canceled, Completed, Error}
import cats.effect.concurrent.Ref
import cats.kernel.Semigroup
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.GraphResourceToNTriples
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.{Interval, RebuildStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection.{ElasticSearchProjection, SparqlProjection}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewProjection, CompositeViewSource, CompositeViewState, CompositeViewValue}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.{CompositeBranch, CompositeGraphStream, CompositeProgress}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.GraphResourceToDocument
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemPipe, ElemStream, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Operation.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.stream._
import com.typesafe.scalalogging.Logger
import fs2.{Pipe, Stream}
import monix.bio.{Task, UIO}

import java.util.UUID

/**
  * Definition of a composite view
  */
sealed trait CompositeViewDef extends Product with Serializable {

  /**
    * The view reference
    */
  def ref: ViewRef

  override def toString: String = s"${ref.project}/${ref.viewId}"

}

object CompositeViewDef {

  private val logger: Logger = Logger[CompositeViewDef]

  /**
    * Active view eligible to be run as a projection by the supervisor
    */
  final case class ActiveViewDef(ref: ViewRef, uuid: UUID, rev: Int, value: CompositeViewValue)
      extends CompositeViewDef {

    /**
      * The projection name for this view
      */
    val projection = s"composite-views-${ref.project}-${ref.viewId}-$rev"

    /**
      * The projection metadata for this view
      */
    val metadata: ProjectionMetadata = ProjectionMetadata(
      CompositeViews.entityType.value,
      projection,
      Some(ref.project),
      Some(ref.viewId)
    )
  }

  /**
    * Deprecated view to be cleaned up and removed from the supervisor
    */
  final case class DeprecatedViewDef(ref: ViewRef) extends CompositeViewDef

  /**
    * Create the definition from the state
    */
  def apply(state: CompositeViewState): CompositeViewDef =
    if (state.deprecated)
      DeprecatedViewDef(
        ViewRef(state.project, state.id)
      )
    else
      ActiveViewDef(
        ViewRef(state.project, state.id),
        state.uuid,
        state.rev,
        state.value
      )

  /**
    * Compile the definition with all the required dependencies into a single stream.
    *
    * This stream is divided into two branches, the main and rebuild branches
    *
    * ===Main branch===
    *
    * A non-terminating stream is build for every source which applies its pipe chain to each resource before pushing
    * them to the common Blazegraph namespace.
    *
    * Each of these source stream is then broadcast to every projection defined in the composite view where the common
    * namespace is queried and the result indexed in the associated index/namespace.
    *
    * All the source streams are merged to form the main branch.
    *
    * ===Rebuild branch===
    *
    * When a rebuild strategy is set, an additional stream is built and run following a defined interval when the main
    * branch has processed new resources.
    *
    * A stream that fetches all current resources (that terminates after processing them) is built for every source.
    *
    * It broadcasts immediately the resources to every projection where it follows the same process as the second part
    * of the main branch.
    *
    * @param view
    *   the definition
    * @param spaces
    *   provides dependencies to Elasticsearch and Blazegraph
    * @param compilePipeChain
    *   compile the pipe chain for sources and projections
    * @param graphStream
    *   fetches the data for the view
    * @param compositeProjections
    *   fetches/saves progress and handles restarts
    */
  def compile(
      view: ActiveViewDef,
      spaces: CompositeSpaces,
      compilePipeChain: PipeChain.Compile,
      graphStream: CompositeGraphStream,
      compositeProjections: CompositeProjections
  )(implicit cr: RemoteContextResolution): Task[CompiledProjection] = {
    val metadata                              = view.metadata
    val fetchProgress: UIO[CompositeProgress] = compositeProjections.progress(view.ref, view.rev)

    def compileSource =
      CompositeViewDef.compileSource(view.ref.project, compilePipeChain, graphStream, spaces.commonSink)(_)

    def compileTarget = CompositeViewDef.compileTarget(compilePipeChain, spaces.queryPipe, spaces.targetSink)(_)

    def compileAll(progressRef: Ref[Task, CompositeProgress]) = {
      def rebuild: ElemPipe[Unit, Unit] = CompositeViewDef.rebuild(
        view.ref,
        view.value.rebuildStrategy,
        CompositeViewDef.rebuildWhen(view, progressRef, fetchProgress, graphStream),
        compositeProjections.resetRebuild(view.ref, view.rev)
      )

      compile(
        view,
        fetchProgress,
        compileSource,
        compileTarget,
        rebuild,
        compositeProjections.handleRestarts(view.ref),
        compositeProjections.saveOperation(metadata, view.ref, view.rev, _, _)
      ).map { stream =>
        CompiledProjection.fromStream(
          metadata,
          ExecutionStrategy.TransientSingleNode,
          _ => stream
        )
      }
    }

    for {
      initProgress <- fetchProgress.absorb
      progressRef  <- Ref.of[Task, CompositeProgress](initProgress)
      projection   <- compileAll(progressRef)
    } yield projection
  }

  /**
    * Compile the composite views into a unique stream.
    *
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
    * @param restarts
    *   to handle restarts of the indexing process of the composite view
    * @param closeBranch
    *   the operation to apply at the end of the branches
    */
  def compile(
      view: ActiveViewDef,
      fetchProgress: UIO[CompositeProgress],
      compileSource: CompositeViewSource => Task[(Iri, Source, Source, Operation)],
      compileTarget: CompositeViewProjection => Task[(Iri, Operation)],
      rebuild: ElemPipe[Unit, Unit],
      restarts: ElemPipe[Unit, Unit],
      closeBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): Task[ElemStream[Unit]] = {
    // We override the default implementation in FS2 (where it appends the two streams)
    implicit val semigroup: Semigroup[ElemStream[Unit]] = (x: ElemStream[Unit], y: ElemStream[Unit]) => x.merge(y)

    val sources = NonEmptyChain.fromNonEmptyList(view.value.sources.toNonEmptyList)
    val targets = NonEmptyChain.fromNonEmptyList(view.value.projections.toNonEmptyList)

    def startLog(sourceId: Iri, branch: String)                                  =
      Task.delay(logger.debug(s"Running '$branch' branch for source '{}' of composite view '{}'.", sourceId, view.ref))
    def finalizeLog[E](sourceId: Iri, branch: String): ExitCase[E] => Task[Unit] = {
      case Completed =>
        val message = "Completed '{}' branch for source '{}' of composite view '{}'."
        Task.delay(logger.debug(message, branch, sourceId, view.ref))
      case Error(e)  =>
        val message = s"Error raised running '$branch' branch for source '$sourceId' of composite view '${view.ref}'."
        Task.delay(logger.error(message, e))
      case Canceled  =>
        val message = "Cancelled '{}' branch for source '{}' of composite view '{}'."
        Task.delay(logger.debug(message, branch, sourceId, view.ref))
    }

    for {
      compiledSources  <- sources.traverse(compileSource)
      targetOperations <- targets.traverse(compileTarget)
      // Main branches
      mains             = compiledSources.reduceMap { case (sourceId, sourceMain, _, sourceOperation) =>
                            Stream.eval(startLog(sourceId, "main")) >>
                              compileMain(sourceId, sourceMain, sourceOperation, targetOperations, fetchProgress, closeBranch)
                                .onFinalizeCase(finalizeLog(sourceId, "main"))
                          }
      // Rebuild branches
      rebuilds          = rebuild(
                            compiledSources
                              .reduceMap { case (sourceId, _, sourceRebuild, _) =>
                                Stream.eval(startLog(sourceId, "rebuild")) >>
                                  compileRebuild(sourceId, sourceRebuild, targetOperations, fetchProgress, closeBranch)
                                    .onFinalizeCase(finalizeLog(sourceId, "rebuild"))
                              }
                          )
      start             = Stream.eval(
                            fetchProgress.tapEval { progress =>
                              UIO.delay(logger.info(s"Starting composite view '${view.ref}' with offset $progress."))
                            }
                          )
    } yield restarts(start >> (mains |+| rebuilds))
  }

  /**
    * If a rebuild strategy is defined, prepend the provided stream by a cooling time and waits for a trigger, repeating
    * the whole stream indefinitely.
    *
    * @param rebuildStrategy
    *   the strategy
    * @param predicate
    *   the stream which
    */
  def rebuild[A](
      view: ViewRef,
      rebuildStrategy: Option[RebuildStrategy],
      predicate: UIO[Boolean],
      resetProgress: UIO[Unit]
  ): Pipe[Task, A, A] = { stream =>
    rebuildStrategy match {
      case Some(Interval(fixedRate)) =>
        val rebuildWhen       = Stream.awakeEvery[Task](fixedRate).flatMap(_ => Stream.eval(predicate))
        val waitingForRebuild = Stream.never[Task].interruptWhen(rebuildWhen).drain
        Stream.eval(Task.delay(logger.debug(s"Rebuild has been defined at $fixedRate for view '{}'.", view))) >>
          (waitingForRebuild ++ Stream.eval(resetProgress).drain ++ stream).repeat
      case None                      =>
        // No rebuild strategy has been defined
        Stream.eval(Task.delay(logger.debug(s"No rebuild strategy has been defined for view '{}'.", view))) >>
          Stream.empty[Task]
    }
  }

  /**
    * Defines the condition to be met to trigger the rebuild of a composite view
    *
    * The conditions are:
    *
    *   - At least one of the main branches indexed at least a new element
    *   - All the main branches consumed all existing elements
    */
  def rebuildWhen(
      view: ActiveViewDef,
      progressRef: Ref[Task, CompositeProgress],
      fetchProgress: UIO[CompositeProgress],
      graphStream: CompositeGraphStream
  ): UIO[Boolean] = {

    def test(condition: Boolean, message: String): UIO[Boolean] =
      UIO.when(condition)(UIO.delay(logger.info(message))).as(condition)

    def checkSource(
        s: CompositeViewSource,
        progress: CompositeProgress,
        previousProgress: CompositeProgress
    ): UIO[RebuildCondition] =
      progress.sourceMainOffset(s.id).fold(UIO.pure(RebuildCondition.start)) { offset =>
        for {
          diffMain    <- test(
                           !previousProgress.sourceMainOffset(s.id).contains(offset),
                           s"An offset difference has been spotted with previous progress for source '${s.id}' in view '${view.ref}'."
                         )
          diffRebuild <- test(
                           !progress.sourceRebuildOffset(s.id).contains(offset),
                           s"An offset difference has been spotted between main and rebuild for source '${s.id}' in view '${view.ref}'."
                         )
          diffOffset   = diffMain || diffRebuild
          noRemaining <-
            if (diffOffset)
              graphStream.remaining(s, view.ref.project)(offset).map(r => r.isEmpty || r.exists(_.count == 0L))
            else UIO.pure(false)
          _           <- test(noRemaining, s"The main branch for source '${s.id}' in view '${view.ref}' completed indexing.")
        } yield RebuildCondition(diffOffset, noRemaining)
      }

    for {
      newProgress      <- fetchProgress
      previousProgress <- progressRef.getAndSet(newProgress).hideErrors
      condition        <- view.value.sources
                            .reduceMapM(checkSource(_, newProgress, previousProgress))
                            .map { r => r.diffOffset && r.noRemaining }
      _                <- test(condition, s"All conditions are met to trigger the rebuild for view '${view.ref}'.")
    } yield condition
  }

  /**
    * Conditions to trigger a rebuild
    */
  final case class RebuildCondition(diffOffset: Boolean, noRemaining: Boolean)

  object RebuildCondition {

    val start: RebuildCondition = RebuildCondition(diffOffset = false, noRemaining = false)

    implicit val rebuildConditionSemigroup: Semigroup[RebuildCondition] = (x: RebuildCondition, y: RebuildCondition) =>
      RebuildCondition(x.diffOffset || y.diffOffset, x.noRemaining && y.noRemaining)
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
      val sourceOffset = progress.sourceMainOffset(sourceId)
      val main         = for {
        mainTargets <- targets.traverse { case (id, operation) =>
                         targetOperation(
                           progress,
                           CompositeBranch.main(sourceId, id),
                           operation,
                           closeBranch
                         )
                       }
        result      <- source.through(sourceOperation).flatMap(_.broadcastThrough(mainTargets))
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
      val sourceOffset = progress.sourceRebuildOffset(sourceId)
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
        case Right(source) =>
          source.apply(sourceOffset.getOrElse(Offset.start))
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
  ): Either[ProjectionErr, Operation] = {
    //TODO Add leap on target
    val branchProgress = progress.branches.get(branch)
    Operation
      .merge(operation, closeBranch(branch, branchProgress.getOrElse(ProjectionProgress.NoProgress)))
  }

  /**
    * Compiles a composite view source into the main and rebuild sources and the operation to apply after it
    * @param source
    *   the composite view source
    * @param project
    *   the composite view source
    * @param compilePipeChain
    *   how to compile the pipe chain of the composite view source
    * @param graphStream
    *   generates the element stream for the source in the context of a branch
    * @param sink
    *   the sink for the common space
    */
  def compileSource(
      project: ProjectRef,
      compilePipeChain: PipeChain.Compile,
      graphStream: CompositeGraphStream,
      sink: Sink
  )(source: CompositeViewSource): Task[(Iri, Source, Source, Operation)] = Task.fromEither {
    for {
      pipes        <- source.pipeChain.traverse(compilePipeChain)
      // We apply `Operation.tap` as we want to keep the GraphResource for the rest of the stream
      tail         <- Operation.merge(GraphResourceToNTriples, sink).map(_.tap)
      chain         = pipes.fold(NonEmptyChain.one(tail))(NonEmptyChain(_, tail))
      operation    <- Operation.merge(chain)
      // We create the elem stream for the two types of branch
      // The main source produces an infinite stream and waits for new elements
      mainSource    = graphStream.main(source, project)
      // The rebuild one a finite one with only the current elements
      rebuildSource = graphStream.rebuild(source, project)
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
      compilePipeChain: PipeChain.Compile,
      queryPipe: SparqlConstructQuery => Operation,
      targetSink: CompositeViewProjection => Sink
  )(target: CompositeViewProjection)(implicit cr: RemoteContextResolution): Task[(Iri, Operation)] = Task.fromEither {
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

    // Getting from the common space, transforming to json and push to the sink
    val tail =
      NonEmptyChain(query, new GraphResourceToDocument(elasticsearch.context, elasticsearch.includeContext), sink)

    for {
      pipes  <- elasticsearch.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(tail)(NonEmptyChain.one(_) ++ tail)
      result <- Operation.merge(chain)
    } yield elasticsearch.id -> result
  }

  // Compiling a Sparql projection of a composite view
  private def compileSparql(
      sparql: SparqlProjection,
      compilePipeChain: PipeChain.Compile,
      query: Operation,
      sink: Sink
  ) = {

    // Getting from the common space, transforming to n-triples and push to the sink
    val tail = NonEmptyChain(query, GraphResourceToNTriples, sink)

    for {
      pipes  <- sparql.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(tail)(NonEmptyChain.one(_) ++ tail)
      result <- Operation.merge(chain)
    } yield sparql.id -> result
  }

}
