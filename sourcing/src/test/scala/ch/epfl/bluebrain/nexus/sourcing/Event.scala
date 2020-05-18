package ch.epfl.bluebrain.nexus.sourcing

sealed trait Event extends Product with Serializable {
  def rev: Int
}

object Event {
  final case class Incremented(rev: Int, step: Int) extends Event
  final case class Initialized(rev: Int)            extends Event
}
