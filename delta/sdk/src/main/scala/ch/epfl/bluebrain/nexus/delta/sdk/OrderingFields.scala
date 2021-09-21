package ch.epfl.bluebrain.nexus.delta.sdk

trait OrderingFields[A] { self =>

  /**
    * Generates an [[Ordering]] of [[A]] accordingly to the field ''field''. If the passed field is not allowed for
    * ordering of [[A]], return None
    */
  def apply(field: String): Option[Ordering[A]]
}

object OrderingFields {

  /**
    * An ordering that is not valid for any field
    */
  def empty[A]: OrderingFields[A] = _ => None

  /**
    * A helper method to produce an ordering for a certain field
    */
  def apply[A](pf: PartialFunction[String, Ordering[A]]): OrderingFields[A] =
    field => Option.when(pf.isDefinedAt(field))(pf(field))

}
