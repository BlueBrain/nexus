package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ProjectionErr.{CouldNotFindPipeErr, CouldNotFindTypedPipeErr}
import shapeless.Typeable

import java.util.concurrent.ConcurrentHashMap

/**
  * Simple source and pipe definition registry.
  */
final class ReferenceRegistry {

  private val pipes = new ConcurrentHashMap[PipeRef, PipeDef]()

  def lookup(ref: PipeRef): Either[CouldNotFindPipeErr, PipeDef] =
    Option(pipes.get(ref)).toRight(CouldNotFindPipeErr(ref))

  def lookupA[A <: PipeDef: Typeable](ref: PipeRef): Either[ProjectionErr, A] = {
    lookup(ref).flatMap { pipeDef =>
      val A = Typeable[A]
      A.cast(pipeDef) match {
        case Some(value) => Right(value)
        case None        => Left(CouldNotFindTypedPipeErr(ref, A.describe))
      }
    }
  }

  def register(definition: PipeDef): Unit = {
    val _ = pipes.put(definition.ref, definition)
  }
}
