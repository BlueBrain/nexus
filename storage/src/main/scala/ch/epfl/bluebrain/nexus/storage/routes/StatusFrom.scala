package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCode

/**
  * Typeclass definition for ''A''s that can be mapped into a StatusCode.
  *
  * @tparam A generic type parameter
  */
trait StatusFrom[A] {

  /**
    * Computes a [[akka.http.scaladsl.model.StatusCode]] instance from the argument value.
    *
    * @param value the input value
    * @return the status code corresponding to the value
    */
  def apply(value: A): StatusCode
}

object StatusFrom {

  /**
    * Lifts a function ''A => StatusCode'' into a ''StatusFrom[A]'' instance.
    *
    * @param f function from A to StatusCode
    * @tparam A type parameter to map to StatusCode
    * @return a ''StatusFrom'' instance from the argument function
    */
  def apply[A](f: A => StatusCode): StatusFrom[A] = (value: A) => f(value)
}
