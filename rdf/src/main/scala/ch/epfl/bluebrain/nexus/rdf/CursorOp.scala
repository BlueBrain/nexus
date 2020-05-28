package ch.epfl.bluebrain.nexus.rdf

import java.io.Serializable

import ch.epfl.bluebrain.nexus.rdf.Node.IriNode

/**
  * Enumeration type for cursor operations
  */
sealed abstract class CursorOp extends Product with Serializable

object CursorOp {
  final case object Top                        extends CursorOp
  final case object Parent                     extends CursorOp
  final case object Narrow                     extends CursorOp
  final case class Up(predicate: IriNode)      extends CursorOp
  final case class UpSet(predicate: IriNode)   extends CursorOp
  final case class DownSet(predicate: IriNode) extends CursorOp
  final case class Down(predicate: IriNode)    extends CursorOp
}
