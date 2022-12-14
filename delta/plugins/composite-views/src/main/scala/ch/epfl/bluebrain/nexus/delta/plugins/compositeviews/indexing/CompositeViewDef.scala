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

  def compile(
      view: ActiveViewDef,
      fetchProgress: UIO[CompositeProgress],
      compileSource: CompositeViewSource => Task[(Iri, Source, Source, Operation)],
      compileTarget: CompositeViewProjection => Task[(Iri, Operation)],
      rebuild: ElemStream[Unit] => ElemStream[Unit],
      endBranch: (CompositeBranch, ProjectionProgress) => Operation
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
                            compileMain(sourceId, sourceMain, sourceOperation, targetOperations, fetchProgress, endBranch)
                          }
      // Rebuild branches
      rebuilds          = rebuild(
                            compiledSources
                              .reduceMap { case (sourceId, _, sourceRebuild, _) =>
                                compileRebuild(sourceId, sourceRebuild, targetOperations, fetchProgress, endBranch)
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
    * @return
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

  private def compileMain(
      sourceId: Iri,
      source: Source,
      sourceOperation: Operation,
      targets: NonEmptyChain[(Iri, Operation)],
      fetchProgress: UIO[CompositeProgress],
      endBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): ElemStream[Unit] =
    Stream.eval(fetchProgress).flatMap { progress =>
      val sourceOffset = progress.sources.get(sourceId)
      val main         = for {
        leapedSource <- sourceOffset.map(sourceOperation.leapUntil).getOrElse(Right(sourceOperation))
        mainTargets  <- targets.traverse { case (id, operation) =>
                          val branch         = CompositeBranch.main(sourceId, id)
                          val branchProgress = progress.branches.get(branch)
                          branchProgress
                            .map { p => operation.leapUntil(p.offset) }
                            .getOrElse(Right(operation))
                            .flatMap(
                              Operation
                                .merge(_, endBranch(branch, branchProgress.getOrElse(ProjectionProgress.NoProgress)))
                            )
                        }
        result       <- source.through(leapedSource).flatMap(_.broadcastThrough(mainTargets))
      } yield result
      main match {
        case Left(e)       => Stream.raiseError[Task](e)
        case Right(source) => source.apply(sourceOffset.getOrElse(Offset.start))
      }
    }

  private def compileRebuild(
      sourceId: Iri,
      source: Source,
      targets: NonEmptyChain[(Iri, Operation)],
      fetchProgress: UIO[CompositeProgress],
      endBranch: (CompositeBranch, ProjectionProgress) => Operation
  ): ElemStream[Unit] =
    Stream.eval(fetchProgress).flatMap { progress =>
      val sourceOffset = progress.sources.get(sourceId)
      val rebuild      = for {
        rebuildTargets <- targets.traverse { case (id, operation) =>
                            val branch         = CompositeBranch.rebuild(sourceId, id)
                            val branchProgress = progress.branches.get(branch)
                            branchProgress
                              .map { p => operation.leapUntil(p.offset) }
                              .getOrElse(Right(operation))
                              .flatMap(
                                Operation
                                  .merge(_, endBranch(branch, branchProgress.getOrElse(ProjectionProgress.NoProgress)))
                              )
                          }
        result         <- source.broadcastThrough(rebuildTargets)
      } yield result
      rebuild match {
        case Left(e)       => Stream.raiseError[Task](e)
        case Right(source) => source.apply(sourceOffset.getOrElse(Offset.start))
      }
    }

  def compileSource(
      source: CompositeViewSource,
      compilePipeChain: PipeChain.Compile,
      toElemStream: (CompositeViewSource, CompositeBranch.Run) => Source,
      sink: Sink
  ): Task[(Iri, Source, Source, Operation)] = Task.fromEither {
    for {
      pipes        <- source.pipeChain.traverse(compilePipeChain)
      tail         <- Operation.merge(GraphResourceToNTriples, sink).map(_.observe)
      chain         = pipes.fold(NonEmptyChain.one(tail))(NonEmptyChain(_, tail))
      operation    <- Operation.merge(chain)
      mainSource    = toElemStream(source, CompositeBranch.Run.Main)
      rebuildSource = toElemStream(source, CompositeBranch.Run.Rebuild)
    } yield (source.id, mainSource, rebuildSource, operation)
  }

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

  private def compileElasticsearch(
      elasticsearch: ElasticSearchProjection,
      compilePipeChain: PipeChain.Compile,
      query: Operation,
      sink: Sink
  )(implicit cr: RemoteContextResolution) = {

    val head = query
    val tail = NonEmptyChain(new GraphResourceToDocument(elasticsearch.context, elasticsearch.includeContext), sink)

    for {
      pipes  <- elasticsearch.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(NonEmptyChain.one(head))(NonEmptyChain(head, _)) ++ tail
      result <- Operation.merge(chain)
    } yield elasticsearch.id -> result
  }

  private def compileSparql(
      sparql: SparqlProjection,
      compilePipeChain: PipeChain.Compile,
      query: Operation,
      sink: Sink
  ) = {

    val head = query
    val tail = NonEmptyChain(GraphResourceToNTriples, sink)

    for {
      pipes  <- sparql.pipeChain.traverse(compilePipeChain)
      chain   = pipes.fold(NonEmptyChain.one(head))(NonEmptyChain(head, _)) ++ tail
      result <- Operation.merge(chain)
    } yield sparql.id -> result
  }

}
