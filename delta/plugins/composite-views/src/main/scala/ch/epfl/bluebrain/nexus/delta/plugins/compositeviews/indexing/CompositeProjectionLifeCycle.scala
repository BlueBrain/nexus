package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration.toMonixBIOOps
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.{ActiveViewDef, DeprecatedViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewProjection
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.projections.CompositeProjections
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeGraphStream
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ExecutionStrategy.TransientSingleNode
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{CompiledProjection, PipeChain}
import monix.bio.{IO => BIO, Task}

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
    * Destroy the projection related to the view if changes related to indexing need to be applied
    */
  def destroyOnIndexingChange(prev: ActiveViewDef, next: CompositeViewDef): Task[Unit]
}

object CompositeProjectionLifeCycle {

  private val logger: Logger = Logger[CompositeProjectionLifeCycle]

  /**
    * Hook that allows to capture changes to apply before starting the indexing of a composite view
    */
  trait Hook {
    def apply(view: ActiveViewDef): Option[IO[Unit]]
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
      spaces: CompositeSpaces,
      sink: CompositeSinks,
      compositeProjections: CompositeProjections
  ): CompositeProjectionLifeCycle = {
    def init(view: ActiveViewDef): Task[Unit] = spaces.init(view)

    def index(view: ActiveViewDef): Task[CompiledProjection] =
      CompositeViewDef.compile(view, sink, compilePipeChain, graphStream, compositeProjections)

    def destroyAll(view: ActiveViewDef): Task[Unit] =
      for {
        _ <- spaces.destroyAll(view)
        _ <- compositeProjections.deleteAll(view.indexingRef)
      } yield ()

    def destroyProjection(view: ActiveViewDef, projection: CompositeViewProjection): Task[Unit] =
      for {
        _ <- spaces.destroyProjection(view, projection)
        _ <- compositeProjections.partialRebuild(view.ref, projection.id)
      } yield ()

    apply(hooks, init, index, destroyAll, destroyProjection)
  }

  private[indexing] def apply(
      hooks: Set[Hook],
      onInit: ActiveViewDef => Task[Unit],
      index: ActiveViewDef => Task[CompiledProjection],
      destroyAll: ActiveViewDef => Task[Unit],
      destroyProjection: (ActiveViewDef, CompositeViewProjection) => Task[Unit]
  ): CompositeProjectionLifeCycle = new CompositeProjectionLifeCycle {

    override def init(view: ActiveViewDef): Task[Unit] = onInit(view)

    override def build(view: ActiveViewDef): Task[CompiledProjection] = {
      detectHook(view).getOrElse {
        index(view)
      }
    }

    private def detectHook(view: ActiveViewDef) = {
      val initial: Option[IO[Unit]] = None
      hooks.toList
        .foldLeft(initial) { case (acc, hook) =>
          (acc ++ hook(view)).reduceOption(_ >> _)
        }
        .map { task =>
          Task.pure(CompiledProjection.fromTask(view.metadata, TransientSingleNode, task.toUIO))
        }
    }

    override def destroyOnIndexingChange(prev: ActiveViewDef, next: CompositeViewDef): Task[Unit] =
      (prev, next) match {
        case (prev, next) if prev.ref != next.ref                                            =>
          BIO.terminate(new IllegalArgumentException(s"Different views were provided: '${prev.ref}' and '${next.ref}'"))
        case (prev, _: DeprecatedViewDef)                                                    =>
          logger.info(s"View '${prev.ref}' has been deprecated, cleaning up the current one.") >> destroyAll(prev)
        case (prev, nextActive: ActiveViewDef) if prev.indexingRev != nextActive.indexingRev =>
          logger.info(s"View '${prev.ref}' sources have changed, cleaning up the current one..") >> destroyAll(prev)
        case (prev, nextActive: ActiveViewDef)                                               =>
          checkProjections(prev, nextActive)
      }

    private def checkProjections(prev: ActiveViewDef, nextActive: ActiveViewDef) =
      prev.projections.nonEmptyTraverse { prevProjection =>
        nextActive.projection(prevProjection.id) match {
          case Right(nextProjection) =>
            Task.when(prevProjection.indexingRev != nextProjection.indexingRev)(
              logger.info(
                s"Projection ${prevProjection.id} of view '${prev.ref}' has changed, cleaning up the current one.."
              ) >>
                destroyProjection(prev, prevProjection)
            )
          case Left(_)               =>
            logger.info(
              s"Projection ${prevProjection.id} of view '${prev.ref}' was removed, cleaning up the current one.."
            ) >>
              destroyProjection(prev, prevProjection)
        }
      }.void
  }

}
