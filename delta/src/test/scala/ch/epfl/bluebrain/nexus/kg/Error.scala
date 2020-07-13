package ch.epfl.bluebrain.nexus.kg

import shapeless.Typeable

/**
  * An error representation that can be uniquely identified by its code.
  *
  * @param `@type`    the unique code for this error
  * @param reason     an a reason for why the error occurred
  * @param `@context` the JSON-LD context
  */
final case class Error(`@type`: String, reason: String, `@context`: String) {
  def tpe: String = `@type`
}

object Error {

  /**
    * Provides the class name for ''A''s that have a [[shapeless.Typeable]] typeclass instance.
    *
    * @tparam A a generic type parameter
    * @return class name of A
    */
  final def classNameOf[A: Typeable]: String = {
    val describe = implicitly[Typeable[A]].describe
    val dotIdx   = describe.lastIndexOf('.')
    if (dotIdx == -1) describe
    else describe.substring(0, dotIdx)
  }
}
