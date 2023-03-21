package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

/**
  * A reference to a Source definition. It allows referring to sources in projection definitions such that they can be
  * looked up during the construction of a runnable projection.
  * @param label
  *   the source label
  */
final case class SourceRef(label: Label)

object SourceRef {

  /**
    * Creates a source reference from a string without verifying the [[Label]] constraints.
    *
    * @param string
    *   the underlying source reference
    */
  def unsafe(string: String): SourceRef =
    SourceRef(Label.unsafe(string))
}
