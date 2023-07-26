package ch.epfl.bluebrain.nexus.delta.sourcing

import doobie.util.fragment.Fragment

/**
  * A type class that provides a conversion from a value of type `A` to a doobie [[Fragment]].
  */
trait FragmentEncoder[A] {

  def apply(value: A): Option[Fragment]

}

object FragmentEncoder {

  /**
    * Construct an instance from a function
    */
  def instance[A](f: A => Option[Fragment]): FragmentEncoder[A] = (value: A) => f(value)

}
