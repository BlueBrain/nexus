package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

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
  * @param project
  *   an optional project reference associated with the projection
  * @param resourceId
  *   an optional resource id associated with the projection
  * @param sources
  *   the collection of source chains
  * @param pipes
  *   the collection of pipe chains
  */
final case class ProjectionDef(
    name: String,
    project: Option[ProjectRef],
    resourceId: Option[Iri],
    sources: NonEmptyChain[SourceChain],
    pipes: NonEmptyChain[PipeChain]
) {

  /**
    * Attempts to compile the projection definition that can be later managed.
    * @param registry
    *   the registry for looking up source and pipe references
    */
  def compile(registry: ReferenceRegistry): Either[ProjectionErr, CompiledProjection] =
    for {
      compiledSources <- sources.traverse(_.compile(registry))
      mergedSources   <- compiledSources.tail.foldLeftM(compiledSources.head)((acc, e) => acc.merge(e))
      compiledPipes   <- pipes.traverse(pc => pc.compile(registry).map(pipe => (pc.id, pipe)))
      source          <- mergedSources.broadcastThrough(compiledPipes)
    } yield CompiledProjection(name, project, resourceId, offset => _ => _ => source.apply(offset))
}
