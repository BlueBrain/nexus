package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import cats.data.NonEmptyChain

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
  * @param metadata
  *   the projection metadata
  * @param sources
  *   the collection of source chains
  * @param pipes
  *   the collection of pipe chains
  */
final case class ProjectionDef(
    metadata: ProjectionMetadata,
    sources: NonEmptyChain[SourceChain],
    pipes: NonEmptyChain[PipeChain]
)
