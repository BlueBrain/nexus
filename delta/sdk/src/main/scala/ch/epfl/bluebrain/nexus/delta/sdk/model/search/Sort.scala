package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Sort.OrderType
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Sort.OrderType.{Asc, Desc}
import io.circe.Encoder

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
    final case object Desc extends OrderType {
      override def toString: String = "desc"
    }

    /**
      * Ascending ordering
      */
    final case object Asc extends OrderType {
      override def toString: String = "asc"
    }

    implicit val orderTypeEncoder: Encoder[OrderType] = Encoder.encodeString.contramap(_.toString)

  }

}
