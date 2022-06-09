package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{CouldNotFindPipeErr, CouldNotFindSourceErr}

import java.util.concurrent.ConcurrentHashMap

/**
  * Simple source and pipe definition registry.
  */
final class ReferenceRegistry {

  private val sources = new ConcurrentHashMap[SourceRef, SourceDef]()
  private val pipes   = new ConcurrentHashMap[PipeRef, PipeDef]()

  def lookup(ref: SourceRef): Either[CouldNotFindSourceErr, SourceDef] =
    Option(sources.get(ref)).toRight(CouldNotFindSourceErr(ref))

  def lookup(ref: PipeRef): Either[CouldNotFindPipeErr, PipeDef] =
    Option(pipes.get(ref)).toRight(CouldNotFindPipeErr(ref))

  def register(definition: SourceDef): Unit = {
    val _ = sources.put(definition.reference, definition)
  }

  def register(definition: PipeDef): Unit = {
    val _ = pipes.put(definition.reference, definition)
  }
}
