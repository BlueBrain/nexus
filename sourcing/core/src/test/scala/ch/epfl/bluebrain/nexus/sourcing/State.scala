package ch.epfl.bluebrain.nexus.sourcing

sealed trait State extends Product with Serializable

object State {
  final case object Initial                      extends State
  final case class Current(rev: Int, value: Int) extends State
}
