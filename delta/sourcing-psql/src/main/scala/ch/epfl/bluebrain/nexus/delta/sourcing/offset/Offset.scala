package ch.epfl.bluebrain.nexus.delta.sourcing.offset

import cats.Order
import doobie.implicits._
import doobie.util.fragment.Fragment

sealed trait Offset extends Product with Serializable {

  def after: Option[Fragment]

}

object Offset {

  /**
    * To fetch all rows from the beginning
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

  def at(value: Long): Offset = At(value)

  implicit val offsetOrder: Order[Offset] = Order.from {
    case (Start, Start) => 0
    case (Start, At(_)) => -1
    case (At(_), Start) => 1
    case (At(x), At(y)) => Order.compare(x, y)
  }

}
