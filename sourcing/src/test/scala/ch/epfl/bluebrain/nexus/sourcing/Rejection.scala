package ch.epfl.bluebrain.nexus.sourcing

sealed trait Rejection extends Product with Serializable

object Rejection {
  final case class InvalidRevision(rev: Int) extends Rejection
}
