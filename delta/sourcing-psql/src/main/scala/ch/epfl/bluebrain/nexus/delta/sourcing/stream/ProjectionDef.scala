package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._

/**
  * A projection definition to be used for materializing a [[Projection]]. It is assembled from a number of
  * [[SourceChain]] that are merged together and broadcast through a number of [[PipeChain]]. It contains information
  * about how this projection is supposed to be managed in terms of passivation and rebuild.
  *
  * A projection has the following structure:
  *
  * {{{
  *   ┌──────────────────────────┐                     ┌────────────────────────┐
  *   |SourceChain               │                     │PipeChain               │
  *   | ┌──────┐  ┌────┐  ┌────┐ │ merge     broadcast │ ┌────┐  ┌────┐  ┌────┐ │ merge
  *   │ │Source├─►│Pipe├─►│Pipe│ ├──────┐   ┌─────────►│ │Pipe├─►│Pipe├─►│Pipe│ ├──────┐
  *   │ └──────┘  └────┘  └────┘ │      │   │          │ └────┘  └────┘  └────┘ │      │
  *   └──────────────────────────┘      │   │          └────────────────────────┘      │
  *                                     ├───┤                                          ├──►
  *   ┌──────────────────────────┐      │   │          ┌────────────────────────┐      │
  *   │SourceChain               │      │   │          │PipeChain               │      │
  *   │ ┌──────┐  ┌────┐  ┌────┐ │      │   │          │ ┌────┐  ┌────┐  ┌────┐ │      │
  *   │ │Source├─►│Pipe├─►│Pipe│ ├──────┘   └─────────►│ │Pipe├─►│Pipe├─►│Pipe│ ├──────┘
  *   │ └──────┘  └────┘  └────┘ │ merge     broadcast │ └────┘  └────┘  └────┘ │ merge
  *   └──────────────────────────┘                     └────────────────────────┘
  * }}}
  *
  * @param name
  *   the projection name
  * @param sources
  *   the collection of source chains
  * @param pipes
  *   the collection of pipe chains
  * @param passivationStrategy
  *   a strategy for passivation
  * @param rebuildStrategy
  *   a strategy for rebuild
  */
final case class ProjectionDef(
    name: String,
    sources: NonEmptyChain[SourceChain],
    pipes: NonEmptyChain[PipeChain],
    passivationStrategy: PassivationStrategy,
    rebuildStrategy: RebuildStrategy
) {

  /**
    * Attempts to compile the projection definition that can be later managed.
    * @param registry
    *   the registry for looking up source and pipe references
    */
  def compile(registry: ReferenceRegistry): Either[ProjectionErr, CompiledProjection] =
    for {
      compiledSources <- sources.traverse(_.compile(registry))
      mergedSources   <- compiledSources.foldLeftM(compiledSources.head)((acc, e) => acc.merge(e))
      compiledPipes   <- pipes.traverse(_.compile(registry))
      source          <- mergedSources.broadcastThrough(compiledPipes)
    } yield new CompiledProjection(name, source, passivationStrategy, rebuildStrategy)

}
