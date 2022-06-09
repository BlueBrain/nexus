package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * A reference to a Pipe definition. It allows referring to pipes in projection definitions such that they can be
  * looked up during the construction of a runnable projection.
  * @param label
  *   the pipe label
  */
final case class PipeRef(label: Label)

object PipeRef {

  /**
    * Creates a pipe reference from a string without verifying the [[Label]] constraints.
    *
    * @param string
    *   the underlying pipe reference
    */
  def unsafe(string: String): PipeRef =
    PipeRef(Label.unsafe(string))

}
