package ch.epfl.bluebrain.nexus.commons.search

import cats.Show
import ch.epfl.bluebrain.nexus.commons.search.Sort.OrderType._
import ch.epfl.bluebrain.nexus.commons.search.Sort._

/**
  * Data type of a ''value'' to be sorted
  *
  * @param order the order (ascending or descending) of the sorting value
  * @param value the value to be sorted
  */
final case class Sort(order: OrderType, value: String)

object Sort {

  /**
    * Attempt to construct a [[Sort]] from a string
    *
    * @param value the string
    */
  final def apply(value: String): Sort =
    value take 1 match {
      case "-" => Sort(Desc, value.drop(1))
      case "+" => Sort(Asc, value.drop(1))
      case _   => Sort(Asc, value)
    }

  /**
    * Enumeration type for all possible ordering
    */
  sealed trait OrderType extends Product with Serializable

  object OrderType {

    /**
      * Descending ordering
      */
    final case object Desc extends OrderType

    /**
      * Ascending ordering
      */
    final case object Asc extends OrderType

    implicit val showOrderType: Show[OrderType] = Show.show {
      case Asc  => "asc"
      case Desc => "desc"
    }

  }

}
