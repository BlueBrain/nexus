package ch.epfl.bluebrain.nexus.delta.sdk

/**
  * Tramsforms a value of type A in a value of type B
  */
trait Mapper[-A, B] {

  /**
    * Transforms from A to B
    * @param value the value to transform
    */
  def to(value: A): B

}

object Mapper {
  implicit def mapperIdentity[A]: Mapper[A, A] = (value: A) => value
}
