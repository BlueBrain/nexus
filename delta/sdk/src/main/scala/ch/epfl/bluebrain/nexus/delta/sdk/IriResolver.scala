package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

/**
  * Allows to resolve an [[A]] to an Iri
  * @tparam A
  */
trait IriResolver[A] {

  /**
    * Resolve [[A]] to an Iri
    */
  def resolve(a: A): Iri
}
