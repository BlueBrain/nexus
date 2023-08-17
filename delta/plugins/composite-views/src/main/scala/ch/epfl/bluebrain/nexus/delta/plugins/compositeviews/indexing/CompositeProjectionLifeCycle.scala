package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeGraphStream
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.TransientSingleNode
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, PipeChain}
import monix.bio.Task

/**
  * Handle the different life stages of a composite view projection
  */
trait CompositeProjectionLifeCycle {

  /**
    * Initialise the projection related to the view
    */
  def init(view: ActiveViewDef): Task[Unit]

  /**
    * Build the projection related to the view, applying any matching hook If none, start the regular indexing
    */
  def build(view: ActiveViewDef): Task[CompiledProjection]

  /**
    * Destroy the projection related to the view
    */
  def destroy(view: ActiveViewDef): Task[Unit]
}

object CompositeProjectionLifeCycle {

  /**
    * Hook that allows to capture changes to apply before starting the indexing of a composite view
    */
  trait Hook {
    def apply(view: ActiveViewDef): Option[Task[Unit]]
  }

  /**
    * A default implementation that does nothing
    */
  val NoopHook: Hook = (_: ActiveViewDef) => None

  /**
    * Constructs the lifecycle of the projection of a composite view including building the projection itself and how to
    * create/destroy the namespaces and indices related to it
    */
  def apply(
      hooks: Set[Hook],
      compilePipeChain: PipeChain.Compile,
      graphStream: CompositeGraphStream,
      buildSpaces: ActiveViewDef => CompositeSpaces,
      compositeProjections: CompositeProjections
  ): CompositeProjectionLifeCycle = {
    def init(view: ActiveViewDef): Task[Unit] = buildSpaces(view).init

    def index(view: ActiveViewDef): Task[CompiledProjection] =
      CompositeViewDef.compile(view, buildSpaces(view), compilePipeChain, graphStream, compositeProjections)

    def destroy(view: ActiveViewDef): Task[Unit] =
      for {
        _ <- buildSpaces(view).destroy
        _ <- compositeProjections.deleteAll(view.indexingRef)
      } yield ()

    apply(hooks, init, index, destroy)
  }

  private[indexing] def apply(
      hooks: Set[Hook],
      onInit: ActiveViewDef => Task[Unit],
      index: ActiveViewDef => Task[CompiledProjection],
      onDestroy: ActiveViewDef => Task[Unit]
  ): CompositeProjectionLifeCycle = new CompositeProjectionLifeCycle {

    override def init(view: ActiveViewDef): Task[Unit] = onInit(view)

    override def build(view: ActiveViewDef): Task[CompiledProjection] = {
      detectHook(view).getOrElse {
        index(view)
      }
    }

    private def detectHook(view: ActiveViewDef) = {
      val initial: Option[Task[Unit]] = None
      hooks.toList
        .foldLeft(initial) { case (acc, hook) =>
          (acc ++ hook(view)).reduceOption(_ >> _)
        }
        .map { task =>
          Task.pure(CompiledProjection.fromTask(view.metadata, TransientSingleNode, task))
        }
    }

    override def destroy(view: ActiveViewDef): Task[Unit] = onDestroy(view)
  }

}
