package ch.epfl.bluebrain.nexus.delta.sdk

/**
  * Allows to resolve an ''S'' to an ''A''
  */
trait Lens[S, A] {

  /**
    * Get the ''A'' from an ''S''
    */
  def get(a: S): A
}
