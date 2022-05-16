package ch.epfl.bluebrain.nexus.delta.sourcing.offset

import doobie.implicits._
import doobie.util.fragment.Fragment

sealed trait Offset extends Product with Serializable {

  def after: Option[Fragment]

}

object Offset {

  /**
    * To fetch all rows
    */
  final case object Start extends Offset {
    override def after: Option[Fragment] = None
  }

  /**
    * To fetch rows from the given offset
    */
  final case class At(value: Long) extends Offset {
    override def after: Option[Fragment] = Some(fr"ordering > $value")
  }

}
